package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.checker.common.ComicInfo;
import com.checker.common.ComicInfoInjector;
import com.checker.common.Constants;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.ArchiveSyncReviewEntity;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.ArchiveSyncReviewMapper;
import com.checker.mapper.EhGalleriesMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class ArchiveSyncService {
    private static final Pattern LEADING_GID = Pattern.compile("^\\[\\d+]\\s*");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(?:cbz|zip)$");
    private static final int MAX_FUZZY_CANDIDATES = 8;
    private static final Set<String> FRAGMENT_STOP_WORDS = Set.of(
            "chinese", "english", "japanese", "translated", "translation", "digital",
            "rewrite", "decensored", "uncensored", "complete", "ongoing");

    private final EhGalleriesMapper galleriesMapper;
    private final ArchiveSyncReviewMapper reviewMapper;
    private final SynologyArchiveReader archiveReader;
    private final SynologyUploadService uploadService;
    private final EhNetworkConfig networkConfig;
    private final TaskExecutor executor;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Set<Long> activeRepairs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger scanned = new AtomicInteger();
    private volatile int total;
    private volatile String scanError;
    private volatile Date scanStartedAt;
    private volatile Date scanFinishedAt;

    public ArchiveSyncService(EhGalleriesMapper galleriesMapper,
                              ArchiveSyncReviewMapper reviewMapper,
                              SynologyArchiveReader archiveReader,
                              SynologyUploadService uploadService,
                              EhNetworkConfig networkConfig,
                              @Qualifier("backgroundTaskExecutor") TaskExecutor executor) {
        this.galleriesMapper = galleriesMapper;
        this.reviewMapper = reviewMapper;
        this.archiveReader = archiveReader;
        this.uploadService = uploadService;
        this.networkConfig = networkConfig;
        this.executor = executor;
    }

    public synchronized Map<String, Object> startScan() {
        if (!scanning.compareAndSet(false, true)) {
            throw new IllegalStateException("已有群晖归档同步扫描正在运行");
        }
        if (!activeRepairs.isEmpty()) {
            scanning.set(false);
            throw new IllegalStateException("仍有归档正在同步，请等待完成后再重新扫描");
        }
        scanned.set(0);
        total = 0;
        scanError = null;
        scanStartedAt = new Date();
        scanFinishedAt = null;
        try {
            executor.execute(this::scan);
        } catch (RuntimeException failure) {
            scanning.set(false);
            throw failure;
        }
        return scanStatus();
    }

    public Map<String, Object> scanStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", scanning.get());
        status.put("total", total);
        status.put("scanned", scanned.get());
        status.put("error", scanError);
        status.put("startedAt", scanStartedAt);
        status.put("finishedAt", scanFinishedAt);
        QueryWrapper<ArchiveSyncReviewEntity> pending = new QueryWrapper<>();
        pending.in("status", List.of("PENDING", "FAILED"));
        status.put("reviewCount", reviewMapper.selectCount(pending));
        return status;
    }

    private void scan() {
        try {
            List<String> archives = archiveReader.listArchives().stream()
                    .filter(ArchiveSyncService::isArchive)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            Set<String> exactArchiveNames = new HashSet<>(archives);

            QueryWrapper<EhGalleriesEntity> galleryQuery = new QueryWrapper<>();
            galleryQuery.isNotNull("filename").ne("filename", "").orderByAsc("gid");
            List<EhGalleriesEntity> galleries = galleriesMapper.selectList(galleryQuery);
            total = galleries.size();

            reviewMapper.delete(new QueryWrapper<>());
            for (EhGalleriesEntity gallery : galleries) {
                String expected = gallery.getFilename();
                if (!exactArchiveNames.contains(expected)) {
                    MatchResult match = findCandidates(gallery, archives);
                    ArchiveSyncReviewEntity review = new ArchiveSyncReviewEntity();
                    review.setGid(gallery.getGid());
                    review.setTitle(gallery.getTitle());
                    review.setExpectedFilename(expected);
                    review.setCandidateFilenames(match.filenames());
                    review.setSelectedFilename(match.filenames().size() == 1 ? match.filenames().get(0) : null);
                    review.setMatchType(match.type());
                    review.setStatus("PENDING");
                    review.setMessage(match.message());
                    reviewMapper.insert(review);
                }
                scanned.incrementAndGet();
            }
        } catch (Exception failure) {
            scanError = rootMessage(failure);
        } finally {
            scanFinishedAt = new Date();
            scanning.set(false);
        }
    }

    public synchronized ArchiveSyncReviewEntity synchronize(Long gid, String requestedFilename) {
        if (scanning.get()) throw new IllegalStateException("扫描进行中，请等待扫描完成后再处理");
        ArchiveSyncReviewEntity review = requireReview(gid);
        if (requestedFilename == null || requestedFilename.isBlank()) {
            throw new IllegalArgumentException("请填写群晖中的原文件名");
        }
        String actualFilename;
        try {
            actualFilename = archiveReader.listArchives().stream()
                    .filter(name -> name.equalsIgnoreCase(requestedFilename.trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("群晖中不存在该文件: " + requestedFilename));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("校验群晖文件失败: " + rootMessage(failure), failure);
        }

        UpdateWrapper<ArchiveSyncReviewEntity> claim = new UpdateWrapper<>();
        claim.eq("gid", gid).in("status", List.of("PENDING", "FAILED"))
                .set("status", "SYNCING").set("selected_filename", actualFilename).set("message", null);
        if (reviewMapper.update(null, claim) != 1) {
            throw new IllegalStateException("该记录正在处理或已经完成");
        }
        activeRepairs.add(gid);
        try {
            executor.execute(() -> repairArchive(gid, actualFilename));
        } catch (RuntimeException failure) {
            activeRepairs.remove(gid);
            ArchiveSyncReviewEntity update = new ArchiveSyncReviewEntity();
            update.setGid(gid);
            update.setStatus("FAILED");
            update.setMessage("无法启动后台同步: " + rootMessage(failure));
            reviewMapper.updateById(update);
            throw failure;
        }
        return reviewMapper.selectById(gid);
    }

    private void repairArchive(Long gid, String actualFilename) {
        Path local = null;
        try {
            EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
            ArchiveSyncReviewEntity review = requireReview(gid);
            if (gallery == null) throw new IllegalStateException("画廊记录不存在");
            String targetFilename = review.getExpectedFilename();
            local = createTempArchive(gid);
            Path target = local;
            archiveReader.read(actualFilename, input -> {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                return null;
            });

            boolean injected = false;
            if (!hasComicInfo(local)) {
                ComicInfoInjector.inject(local, buildComicInfo(gallery));
                injected = true;
            }

            if (injected) {
                uploadService.upload(local, targetFilename);
                if (!actualFilename.equalsIgnoreCase(targetFilename)) archiveReader.delete(actualFilename);
            } else if (!actualFilename.equals(targetFilename)) {
                renameToDatabaseFilename(gid, actualFilename, targetFilename);
            }

            ArchiveSyncReviewEntity update = new ArchiveSyncReviewEntity();
            update.setGid(gid);
            update.setSelectedFilename(targetFilename);
            update.setStatus("COMPLETED");
            update.setMessage(injected ? "已补写 ComicInfo.xml 并按数据库文件名发布" : "已按数据库文件名完成同步");
            reviewMapper.updateById(update);
        } catch (Exception failure) {
            ArchiveSyncReviewEntity update = new ArchiveSyncReviewEntity();
            update.setGid(gid);
            update.setStatus("FAILED");
            update.setMessage(truncate(rootMessage(failure)));
            reviewMapper.updateById(update);
        } finally {
            activeRepairs.remove(gid);
            if (local != null) {
                try {
                    Files.deleteIfExists(local);
                    Files.deleteIfExists(local.resolveSibling(local.getFileName() + ".inject.tmp"));
                } catch (IOException ignored) {
                    // Temporary files are safe to clean up on the next OS maintenance pass.
                }
            }
        }
    }

    public void markRedownloadStarted(Long gid, String workflowId) {
        requireReview(gid);
        ArchiveSyncReviewEntity update = new ArchiveSyncReviewEntity();
        update.setGid(gid);
        update.setStatus("REDOWNLOAD_STARTED");
        update.setMessage("已启动重新抓取下载: " + workflowId);
        reviewMapper.updateById(update);
    }

    public synchronized ArchiveSyncReviewEntity claimRedownload(Long gid) {
        if (scanning.get()) throw new IllegalStateException("扫描进行中，请等待扫描完成后再处理");
        ArchiveSyncReviewEntity review = requireReview(gid);
        UpdateWrapper<ArchiveSyncReviewEntity> claim = new UpdateWrapper<>();
        claim.eq("gid", gid).in("status", List.of("PENDING", "FAILED", "REDOWNLOAD_STARTED"))
                .set("status", "REDOWNLOAD_STARTING").set("message", "正在启动重新下载流程");
        if (reviewMapper.update(null, claim) != 1) {
            throw new IllegalStateException("该记录正在处理或已经完成");
        }
        return review;
    }

    public void markRedownloadFailed(Long gid, Throwable failure) {
        ArchiveSyncReviewEntity update = new ArchiveSyncReviewEntity();
        update.setGid(gid);
        update.setStatus("FAILED");
        update.setMessage("启动重新下载失败: " + truncate(rootMessage(failure)));
        reviewMapper.updateById(update);
    }

    public ArchiveSyncReviewEntity requireReview(Long gid) {
        ArchiveSyncReviewEntity review = gid == null ? null : reviewMapper.selectById(gid);
        if (review == null) throw new IllegalArgumentException("同步审核记录不存在，请先重新扫描");
        return review;
    }

    public Map<String, Long> databaseFilenameOwners() {
        QueryWrapper<EhGalleriesEntity> query = new QueryWrapper<>();
        query.select("gid", "filename").isNotNull("filename").ne("filename", "");
        Map<String, Long> owners = new HashMap<>();
        for (EhGalleriesEntity gallery : galleriesMapper.selectList(query)) {
            owners.put(gallery.getFilename().toLowerCase(Locale.ROOT), gallery.getGid());
        }
        return owners;
    }

    static MatchResult findCandidates(EhGalleriesEntity gallery, List<String> archives) {
        if (gallery == null || gallery.getGid() == null || archives == null) {
            return new MatchResult("MISSING", List.of(), "未找到候选文件");
        }
        String gidPrefix = "[" + gallery.getGid() + "]";
        List<String> gidMatches = archives.stream()
                .filter(name -> name.regionMatches(true, 0, gidPrefix, 0, gidPrefix.length()))
                .filter(name -> name.length() == gidPrefix.length()
                        || Character.isWhitespace(name.charAt(gidPrefix.length())))
                .toList();
        if (!gidMatches.isEmpty()) {
            return new MatchResult("GID", gidMatches,
                    gidMatches.size() == 1 ? "按 GID 找到唯一旧文件" : "按 GID 找到多个候选文件，需人工确认");
        }

        List<String> titles = new ArrayList<>();
        addNormalized(titles, gallery.getTitle());
        addNormalized(titles, gallery.getOriginalTitle());
        List<String> fragments = new ArrayList<>();
        addFragments(fragments, gallery.getTitle());
        addFragments(fragments, gallery.getOriginalTitle());
        List<String> titleMatches = archives.stream()
                .filter(name -> {
                    String normalized = normalizeArchiveName(name);
                    return titles.stream().anyMatch(title -> title.length() >= 6
                            && (normalized.contains(title) || title.contains(normalized)));
                }).toList();
        if (!titleMatches.isEmpty()) {
            return new MatchResult("TITLE", titleMatches,
                    titleMatches.size() == 1 ? "按完整画廊名称找到唯一旧文件" : "按完整画廊名称找到多个候选文件，需人工确认");
        }

        List<ScoredFilename> scored = new ArrayList<>();
        for (String archive : archives) {
            String normalized = normalizeArchiveName(archive);
            double score = titles.stream().mapToDouble(title -> diceSimilarity(title, normalized)).max().orElse(0);
            double fragmentScore = fragments.stream().filter(normalized::contains)
                    .mapToDouble(fragment -> Math.min(0.78, 0.42 + fragment.length() / 80D))
                    .max().orElse(0);
            score = Math.max(score, fragmentScore);
            if (score >= 0.35) scored.add(new ScoredFilename(archive, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredFilename::score).reversed()
                .thenComparing(ScoredFilename::filename, String.CASE_INSENSITIVE_ORDER));
        if (!scored.isEmpty()) {
            double floor = Math.max(0.35, scored.get(0).score() - 0.12);
            List<String> fuzzy = scored.stream().filter(item -> item.score() >= floor)
                    .limit(MAX_FUZZY_CANDIDATES).map(ScoredFilename::filename).toList();
            return new MatchResult("FUZZY", fuzzy,
                    fuzzy.size() == 1 ? "按标题片段模糊匹配到唯一候选" : "按标题片段找到多个候选文件，需人工确认");
        }
        return new MatchResult("MISSING", List.of(), "GID、完整标题和标题片段均未匹配到文件");
    }

    private static void addNormalized(List<String> values, String value) {
        String normalized = normalize(value);
        if (normalized.length() >= 4 && !values.contains(normalized)) values.add(normalized);
    }

    private static void addFragments(List<String> values, String value) {
        if (value == null) return;
        String normalizedValue = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String part : normalizedValue.split("[\\p{P}\\p{S}\\s]+")) {
            String fragment = normalize(part);
            if (fragment.length() >= 6 && !FRAGMENT_STOP_WORDS.contains(fragment)
                    && !values.contains(fragment)) values.add(fragment);
        }
    }

    private static String normalizeArchiveName(String filename) {
        String withoutExtension = EXTENSION.matcher(filename == null ? "" : filename).replaceFirst("");
        return normalize(LEADING_GID.matcher(withoutExtension).replaceFirst(""));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static double diceSimilarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 1;
        if (left.length() < 2 || right.length() < 2) return left.equals(right) ? 1 : 0;
        Map<String, Integer> leftPairs = bigrams(left);
        Map<String, Integer> rightPairs = bigrams(right);
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : leftPairs.entrySet()) {
            overlap += Math.min(entry.getValue(), rightPairs.getOrDefault(entry.getKey(), 0));
        }
        return 2D * overlap / ((left.length() - 1) + (right.length() - 1));
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < value.length() - 1; i++) result.merge(value.substring(i, i + 2), 1, Integer::sum);
        return result;
    }

    private Path createTempArchive(Long gid) throws IOException {
        String configured = networkConfig.getDownload() == null ? null : networkConfig.getDownload().getTempDir();
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(configured);
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "archive-sync-" + gid + "-", ".cbz");
    }

    private void renameToDatabaseFilename(Long gid, String source, String target) throws Exception {
        if (!source.equalsIgnoreCase(target)) {
            archiveReader.rename(source, target);
            return;
        }
        // Case-insensitive SMB shares may treat a case-only rename as an existing target.
        String temporary = ".archive-sync-" + gid + "-" + UUID.randomUUID() + ".cbz";
        archiveReader.rename(source, temporary);
        try {
            archiveReader.rename(temporary, target);
        } catch (Exception failure) {
            try {
                archiveReader.rename(temporary, source);
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static boolean hasComicInfo(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.stream().map(ZipEntry::getName)
                    .anyMatch(ComicInfoInjector.COMIC_INFO_ENTRY::equalsIgnoreCase);
        }
    }

    private static ComicInfo buildComicInfo(EhGalleriesEntity gallery) {
        List<String> writers = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        if (gallery.getTags() != null) {
            for (String tag : gallery.getTags()) {
                if (tag != null && (tag.startsWith("artist:") || tag.startsWith("group:"))) {
                    writers.add(tag.substring(tag.indexOf(':') + 1));
                } else if (tag != null) {
                    tags.add(tag);
                }
            }
        }
        return ComicInfo.builder().title(gallery.getTitle()).series(Constants.KOMGA_TARGET_SERIES)
                .summary(gallery.getSummary()).writers(writers).tags(tags).build();
    }

    private static boolean isArchive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cbz") || lower.endsWith(".zip");
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(950, value.length()));
    }

    record MatchResult(String type, List<String> filenames, String message) {
    }

    private record ScoredFilename(String filename, double score) {
    }
}
