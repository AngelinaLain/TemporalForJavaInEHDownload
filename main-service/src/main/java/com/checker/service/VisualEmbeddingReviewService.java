package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.PerceptualHash;
import com.checker.common.VectorSimilarity;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.VisualEmbedding;
import com.checker.entity.AiPromptVersionEntity;
import com.checker.entity.AiProviderConfigEntity;
import com.checker.entity.AiVisualReviewJobEntity;
import com.checker.entity.AiVisualReviewResultEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.entity.GalleryPageEmbeddingEntity;
import com.checker.mapper.AiVisualReviewJobMapper;
import com.checker.mapper.AiVisualReviewResultMapper;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.mapper.GalleryPageEmbeddingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VisualEmbeddingReviewService {
    private static final int MAX_PAGES = 16;
    private static final Set<String> DECISIONS = Set.of(
            "SAME_CONTENT", "POSSIBLE_VARIANT", "DIFFERENT_CONTENT", "INSUFFICIENT_EVIDENCE");

    private final AiVisualReviewJobMapper jobMapper;
    private final AiVisualReviewResultMapper resultMapper;
    private final GalleryPageEmbeddingMapper embeddingMapper;
    private final EhGalleriesMapper galleriesMapper;
    private final VisualFingerprintService fingerprintService;
    private final SynologyArchiveReader archiveReader;
    private final ArchiveSampleImageExtractor sampleExtractor;
    private final AiInternalClient aiClient;
    private final AiConfigurationService configurationService;
    private final ObjectMapper objectMapper;

    public VisualEmbeddingReviewService(AiVisualReviewJobMapper jobMapper,
                                        AiVisualReviewResultMapper resultMapper,
                                        GalleryPageEmbeddingMapper embeddingMapper,
                                        EhGalleriesMapper galleriesMapper,
                                        VisualFingerprintService fingerprintService,
                                        SynologyArchiveReader archiveReader,
                                        ArchiveSampleImageExtractor sampleExtractor,
                                        AiInternalClient aiClient,
                                        AiConfigurationService configurationService,
                                        ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.resultMapper = resultMapper;
        this.embeddingMapper = embeddingMapper;
        this.galleriesMapper = galleriesMapper;
        this.fingerprintService = fingerprintService;
        this.archiveReader = archiveReader;
        this.sampleExtractor = sampleExtractor;
        this.aiClient = aiClient;
        this.configurationService = configurationService;
        this.objectMapper = objectMapper;
    }

    public void execute(String jobId) throws Exception {
        AiVisualReviewJobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("视觉复核任务不存在");
        if (Set.of("COMPLETED", "COMPLETED_WITH_WARNINGS").contains(job.getStatus())) return;
        job.setStatus("RUNNING");
        job.setStartedAt(new Date());
        job.setFinishedAt(null);
        job.setLastError(null);
        jobMapper.updateById(job);
        try {
            doExecute(job);
        } catch (Exception failure) {
            job.setStatus("FAILED");
            job.setLastError(truncate(rootMessage(failure), 950));
            job.setFinishedAt(new Date());
            jobMapper.updateById(job);
            throw failure;
        }
    }

    private void doExecute(AiVisualReviewJobEntity job) throws Exception {
        EhGalleriesEntity leftGallery = requireGallery(job.getLeftGid());
        EhGalleriesEntity rightGallery = requireGallery(job.getRightGid());
        List<GalleryPageFingerprint> leftHashes = fingerprints(job.getLeftGid());
        List<GalleryPageFingerprint> rightHashes = fingerprints(job.getRightGid());

        Map<String, Object> embeddingStatus = aiClient.embeddingStatus();
        if (!Boolean.TRUE.equals(embeddingStatus.get("available"))) {
            throw new IllegalStateException("ai-service 本地视觉模型不可用，请检查 AI_EMBEDDING_ENABLED 和模型挂载");
        }
        String modelVersion = String.valueOf(embeddingStatus.get("modelVersion"));
        Map<Integer, GalleryPageEmbeddingEntity> leftEmbeddings = ensureEmbeddings(leftGallery, leftHashes, modelVersion);
        Map<Integer, GalleryPageEmbeddingEntity> rightEmbeddings = ensureEmbeddings(rightGallery, rightHashes, modelVersion);

        double ambiguous = decimal(job.getAmbiguousMinSimilarity(), 0.86D);
        double high = decimal(job.getHighSimilarity(), 0.94D);
        Evidence evidence = compare(leftHashes, rightHashes, leftEmbeddings, rightEmbeddings, ambiguous, high);
        AiVisualReviewResultEntity result = toResult(job, evidence);

        boolean warning = false;
        if (!"EMBEDDING_ONLY".equals(job.getAnalysisMode()) && "POSSIBLE_VARIANT".equals(result.getDecision())) {
            try {
                applyLlm(job, leftGallery, rightGallery, evidence, result);
            } catch (Exception llmFailure) {
                warning = true;
                job.setLastError("本地向量分析已完成，但 LLM 复核失败: " + truncate(rootMessage(llmFailure), 850));
                result.setReason(truncate(result.getReason() + "；LLM 复核不可用，已保留本地判断", 1900));
            }
        }
        saveResult(result);
        job.setStatus(warning ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
        job.setFinishedAt(new Date());
        jobMapper.updateById(job);
    }

    private Map<Integer, GalleryPageEmbeddingEntity> ensureEmbeddings(EhGalleriesEntity gallery,
                                                                       List<GalleryPageFingerprint> hashes,
                                                                       String modelVersion) throws Exception {
        List<GalleryPageFingerprint> selected = hashes.stream()
                .sorted(Comparator.comparing(GalleryPageFingerprint::getPageIndex))
                .limit(MAX_PAGES).toList();
        List<GalleryPageEmbeddingEntity> persisted = embeddingMapper.selectList(
                new QueryWrapper<GalleryPageEmbeddingEntity>()
                        .eq("gid", gallery.getGid()).eq("model_version", modelVersion).eq("crop_type", "FULL"));
        Map<Integer, GalleryPageEmbeddingEntity> current = persisted.stream()
                .collect(Collectors.toMap(GalleryPageEmbeddingEntity::getPageIndex, item -> item, (a, b) -> a));
        Set<Integer> missing = new LinkedHashSet<>();
        for (GalleryPageFingerprint hash : selected) {
            GalleryPageEmbeddingEntity existing = current.get(hash.getPageIndex());
            if (existing == null || !sourceFingerprint(hash).equals(existing.getSourceFingerprint())) {
                missing.add(hash.getPageIndex());
            }
        }
        if (!missing.isEmpty()) {
            Map<Integer, ArchiveSampleImageExtractor.SampleImage> images = readSamples(gallery, missing);
            for (GalleryPageFingerprint hash : selected) {
                if (!missing.contains(hash.getPageIndex())) continue;
                ArchiveSampleImageExtractor.SampleImage image = images.get(hash.getPageIndex());
                if (image == null) throw new IllegalStateException("归档中缺少采样页 " + hash.getPageIndex());
                VisualEmbedding embedding = aiClient.embed(image.bytes(), image.pageName());
                if (embedding == null || embedding.getValues() == null || embedding.getValues().length == 0) {
                    throw new IllegalStateException("ai-service 返回了空向量");
                }
                GalleryPageEmbeddingEntity entity = current.get(hash.getPageIndex());
                if (entity == null) entity = new GalleryPageEmbeddingEntity();
                entity.setGid(gallery.getGid());
                entity.setPageIndex(hash.getPageIndex());
                entity.setCropType("FULL");
                entity.setModelName(embedding.getModel());
                entity.setModelVersion(embedding.getModelVersion());
                entity.setDimensions(embedding.getDimensions());
                entity.setEmbedding(EmbeddingCodec.encode(embedding.getValues()));
                entity.setSourceFingerprint(sourceFingerprint(hash));
                if (entity.getId() == null) embeddingMapper.insert(entity); else embeddingMapper.updateById(entity);
                current.put(entity.getPageIndex(), entity);
            }
        }
        return current;
    }

    private Evidence compare(List<GalleryPageFingerprint> leftHashes,
                             List<GalleryPageFingerprint> rightHashes,
                             Map<Integer, GalleryPageEmbeddingEntity> leftEmbeddings,
                             Map<Integer, GalleryPageEmbeddingEntity> rightEmbeddings,
                             double ambiguous, double high) {
        List<PageCandidate> candidates = new ArrayList<>();
        for (GalleryPageFingerprint left : leftHashes) {
            if (!leftEmbeddings.containsKey(left.getPageIndex())) continue;
            for (GalleryPageFingerprint right : rightHashes) {
                if (!rightEmbeddings.containsKey(right.getPageIndex())) continue;
                int distance = Math.min(PerceptualHash.distance(left.getPerceptualHash(), right.getPerceptualHash()),
                        PerceptualHash.distance(left.getCenterHash(), right.getCenterHash()));
                candidates.add(new PageCandidate(left.getPageIndex(), right.getPageIndex(), distance));
            }
        }
        candidates.sort(Comparator.comparingInt(PageCandidate::hashDistance));
        Set<Integer> usedLeft = new HashSet<>();
        Set<Integer> usedRight = new HashSet<>();
        List<PageScore> scores = new ArrayList<>();
        for (PageCandidate candidate : candidates) {
            if (candidate.hashDistance() > 20 || usedLeft.contains(candidate.leftPage())
                    || usedRight.contains(candidate.rightPage())) continue;
            GalleryPageEmbeddingEntity left = leftEmbeddings.get(candidate.leftPage());
            GalleryPageEmbeddingEntity right = rightEmbeddings.get(candidate.rightPage());
            if (!left.getDimensions().equals(right.getDimensions())) continue;
            double cosine = VectorSimilarity.cosine(
                    EmbeddingCodec.decode(left.getEmbedding(), left.getDimensions()),
                    EmbeddingCodec.decode(right.getEmbedding(), right.getDimensions()));
            scores.add(new PageScore(candidate.leftPage(), candidate.rightPage(), candidate.hashDistance(), cosine));
            usedLeft.add(candidate.leftPage());
            usedRight.add(candidate.rightPage());
        }
        if (scores.isEmpty()) return new Evidence(0, 0, 0, 0, 0, List.of(),
                "INSUFFICIENT_EVIDENCE", 0.2D, "没有找到可对齐的采样页");
        double embeddingSimilarity = scores.stream().mapToDouble(PageScore::cosine).average().orElse(0D);
        double hashSimilarity = scores.stream().mapToDouble(score -> 1D - score.hashDistance() / 64D).average().orElse(0D);
        long matched = scores.stream().filter(score -> score.cosine() >= ambiguous).count();
        int compared = scores.size();
        int possible = Math.max(1, Math.min(leftEmbeddings.size(), rightEmbeddings.size()));
        double coverage = matched / (double) possible;
        List<PageScore> ordered = scores.stream().sorted(Comparator.comparingInt(PageScore::leftPage)).toList();
        int monotonic = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).rightPage() > ordered.get(i - 1).rightPage()) monotonic++;
        }
        double order = ordered.size() <= 1 ? 1D : monotonic / (double) (ordered.size() - 1);
        double confidence = clamp(embeddingSimilarity * 0.55D + hashSimilarity * 0.25D
                + coverage * 0.15D + order * 0.05D);
        String decision;
        String reason;
        if (embeddingSimilarity >= high && coverage >= 0.6D) {
            decision = "SAME_CONTENT";
            reason = "向量相似度和采样页覆盖率均达到高相似阈值";
        } else if (embeddingSimilarity >= ambiguous || (hashSimilarity >= 0.82D && coverage >= 0.35D)) {
            decision = "POSSIBLE_VARIANT";
            reason = "存在较强视觉相似证据，但尚不足以自动确认同一内容";
        } else {
            decision = "DIFFERENT_CONTENT";
            confidence = clamp(1D - confidence);
            reason = "向量相似度或页面覆盖率未达到候选阈值";
        }
        return new Evidence(embeddingSimilarity, hashSimilarity, (int) matched, compared, order,
                scores, decision, confidence, reason);
    }

    private void applyLlm(AiVisualReviewJobEntity job, EhGalleriesEntity leftGallery,
                          EhGalleriesEntity rightGallery, Evidence evidence,
                          AiVisualReviewResultEntity result) throws Exception {
        if (job.getProviderId() == null) throw new IllegalStateException("任务没有固定 LLM 供应商");
        AiProviderConfigEntity provider = configurationService.requireProvider(job.getProviderId());
        if (!Boolean.TRUE.equals(provider.getEnabled())) throw new IllegalStateException("LLM 供应商已禁用");
        AiPromptVersionEntity promptVersion = configurationService.requirePrompt(job.getPromptVersionId());
        Map<String, Object> evidencePayload = new LinkedHashMap<>();
        evidencePayload.put("embeddingSimilarity", round(evidence.embeddingSimilarity()));
        evidencePayload.put("perceptualHashSimilarity", round(evidence.hashSimilarity()));
        evidencePayload.put("matchedPages", evidence.matchedPages());
        evidencePayload.put("comparedPages", evidence.comparedPages());
        evidencePayload.put("pageOrderConsistency", round(evidence.orderConsistency()));
        if (Boolean.TRUE.equals(job.getIncludeMetadata()) && Boolean.TRUE.equals(provider.getAllowTextMetadata())) {
            evidencePayload.put("left", metadata(leftGallery));
            evidencePayload.put("right", metadata(rightGallery));
        }
        String prompt = promptVersion.getPrompt() + "\n\n证据：\n"
                + objectMapper.writeValueAsString(evidencePayload);

        List<AiInternalClient.ImagePayload> images = List.of();
        boolean transmitImages = "VISION_LLM".equals(job.getAnalysisMode())
                && Boolean.TRUE.equals(job.getAllowImageTransmission())
                && Boolean.TRUE.equals(provider.getAllowVisualInput());
        if (transmitImages) images = llmImages(leftGallery, rightGallery, evidence, job.getSamplePageCount());
        String output = aiClient.chat(provider, job.getModel(), prompt, images);
        JsonNode parsed = parseJson(output);
        String decision = parsed.path("decision").asText("");
        if (!DECISIONS.contains(decision)) throw new IllegalStateException("LLM decision 不在允许范围内");
        double confidence = parsed.path("confidence").asDouble(-1D);
        if (confidence < 0D || confidence > 1D) throw new IllegalStateException("LLM confidence 不在 0-1 范围内");
        String reason = parsed.path("reason").asText("");
        result.setDecision(decision);
        result.setConfidence(decimal(confidence));
        result.setReason(truncate(reason.isBlank() ? "LLM 未提供原因" : reason, 1900));
        result.setRequiresHumanReview(parsed.path("requiresHumanReview").asBoolean(true));
        result.setLlmUsed(true);
        result.setImagesTransmitted(transmitImages && !images.isEmpty());
    }

    private List<AiInternalClient.ImagePayload> llmImages(EhGalleriesEntity left, EhGalleriesEntity right,
                                                           Evidence evidence, Integer requested) throws Exception {
        int limit = Math.max(1, Math.min(8, requested == null ? 4 : requested));
        LinkedHashSet<Integer> leftPages = new LinkedHashSet<>();
        LinkedHashSet<Integer> rightPages = new LinkedHashSet<>();
        for (PageScore score : evidence.pageScores()) {
            if (leftPages.size() + rightPages.size() >= limit) break;
            leftPages.add(score.leftPage());
            if (leftPages.size() + rightPages.size() < limit) rightPages.add(score.rightPage());
        }
        Map<Integer, ArchiveSampleImageExtractor.SampleImage> leftImages = readSamples(left, leftPages);
        Map<Integer, ArchiveSampleImageExtractor.SampleImage> rightImages = readSamples(right, rightPages);
        List<AiInternalClient.ImagePayload> result = new ArrayList<>();
        for (ArchiveSampleImageExtractor.SampleImage image : leftImages.values()) {
            result.add(new AiInternalClient.ImagePayload(image.mimeType(), image.bytes()));
        }
        for (ArchiveSampleImageExtractor.SampleImage image : rightImages.values()) {
            if (result.size() >= limit) break;
            result.add(new AiInternalClient.ImagePayload(image.mimeType(), image.bytes()));
        }
        return result;
    }

    private Map<Integer, ArchiveSampleImageExtractor.SampleImage> readSamples(
            EhGalleriesEntity gallery, Set<Integer> indexes) throws Exception {
        try (SynologyArchiveReader.ArchiveSession session = archiveReader.openSession()) {
            List<String> directories = new ArrayList<>(new LinkedHashSet<>(List.of(
                    normalizeDirectory(gallery.getStoragePath()),
                    normalizeDirectory(gallery.getSeriesCleanupPath()), "")));
            Exception last = null;
            for (String directory : directories) {
                try {
                    Optional<String> resolved = SynologyArchiveReader.selectSeriesArchive(
                            gallery.getFilename(), gallery.getGid(), session.listArchives(directory));
                    if (resolved.isEmpty()) continue;
                    return session.read(directory, resolved.get(), input -> sampleExtractor.extract(input, indexes));
                } catch (Exception failure) {
                    last = failure;
                }
            }
            throw new IllegalStateException("无法读取 GID " + gallery.getGid() + " 的归档", last);
        }
    }

    private List<GalleryPageFingerprint> fingerprints(Long gid) {
        List<GalleryPageFingerprint> values = fingerprintService.find(gid);
        if (values.isEmpty()) throw new IllegalStateException("GID " + gid + " 尚未生成当前版本的视觉指纹");
        return values.stream().limit(MAX_PAGES).toList();
    }

    private EhGalleriesEntity requireGallery(Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) throw new IllegalArgumentException("画廊不存在: " + gid);
        return gallery;
    }

    private AiVisualReviewResultEntity toResult(AiVisualReviewJobEntity job, Evidence evidence) {
        AiVisualReviewResultEntity result = new AiVisualReviewResultEntity();
        result.setJobId(job.getId());
        result.setDecision(evidence.decision());
        result.setConfidence(decimal(evidence.confidence()));
        result.setEmbeddingSimilarity(decimal(evidence.embeddingSimilarity()));
        result.setPerceptualHashSimilarity(decimal(evidence.hashSimilarity()));
        result.setMatchedPages(evidence.matchedPages());
        result.setComparedPages(evidence.comparedPages());
        result.setPageOrderConsistency(decimal(evidence.orderConsistency()));
        result.setReason(evidence.reason());
        result.setRequiresHumanReview(true);
        result.setLlmUsed(false);
        result.setImagesTransmitted(false);
        return result;
    }

    @Transactional
    protected void saveResult(AiVisualReviewResultEntity result) {
        AiVisualReviewResultEntity existing = resultMapper.selectOne(
                new QueryWrapper<AiVisualReviewResultEntity>().eq("job_id", result.getJobId()));
        if (existing == null) resultMapper.insert(result);
        else {
            result.setId(existing.getId());
            resultMapper.updateById(result);
        }
    }

    private JsonNode parseJson(String output) throws Exception {
        String normalized = output == null ? "" : output.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("LLM 未返回 JSON");
        return objectMapper.readTree(normalized.substring(start, end + 1));
    }

    private static String sourceFingerprint(GalleryPageFingerprint value) throws Exception {
        String source = value.getPerceptualHash() + ":" + value.getCenterHash() + ":" + value.getAlgorithmVersion();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format("%02x", item));
        return hex.toString();
    }

    private static Map<String, Object> metadata(EhGalleriesEntity gallery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gid", gallery.getGid());
        data.put("title", truncate(gallery.getTitle(), 300));
        data.put("pageCount", gallery.getPageCount());
        List<String> tags = gallery.getTags() == null ? List.of() : gallery.getTags().stream().limit(50).toList();
        data.put("tags", tags);
        return data;
    }

    private static String normalizeDirectory(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static double decimal(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(clamp(value)).setScale(5, RoundingMode.HALF_UP);
    }

    private static double round(double value) {
        return Math.round(value * 100000D) / 100000D;
    }

    private static double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record PageCandidate(int leftPage, int rightPage, int hashDistance) {
    }

    private record PageScore(int leftPage, int rightPage, int hashDistance, double cosine) {
    }

    private record Evidence(double embeddingSimilarity, double hashSimilarity, int matchedPages,
                            int comparedPages, double orderConsistency, List<PageScore> pageScores,
                            String decision, double confidence, String reason) {
    }
}
