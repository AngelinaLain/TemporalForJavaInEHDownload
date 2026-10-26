package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.entity.AiProviderConfigEntity;
import com.checker.entity.AiUseCaseConfigEntity;
import com.checker.entity.AiVisualReviewJobEntity;
import com.checker.entity.AiVisualReviewResultEntity;
import com.checker.common.Constants;
import com.checker.temporalServices.workflows.VisualReviewWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import com.checker.mapper.AiVisualReviewJobMapper;
import com.checker.mapper.AiVisualReviewResultMapper;
import com.checker.mapper.EhGalleriesMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Date;

@Service
public class AiVisualReviewJobService {
    private final AiVisualReviewJobMapper jobMapper;
    private final AiVisualReviewResultMapper resultMapper;
    private final EhGalleriesMapper galleriesMapper;
    private final AiConfigurationService configurationService;
    private final WorkflowClient workflowClient;

    public AiVisualReviewJobService(AiVisualReviewJobMapper jobMapper,
                                    AiVisualReviewResultMapper resultMapper,
                                    EhGalleriesMapper galleriesMapper,
                                    AiConfigurationService configurationService,
                                    WorkflowClient workflowClient) {
        this.jobMapper = jobMapper;
        this.resultMapper = resultMapper;
        this.galleriesMapper = galleriesMapper;
        this.configurationService = configurationService;
        this.workflowClient = workflowClient;
    }

    @Transactional
    public AiVisualReviewJobEntity create(long firstGid, long secondGid) {
        if (firstGid <= 0 || secondGid <= 0 || firstGid == secondGid) {
            throw new IllegalArgumentException("请选择两个不同的有效 GID");
        }
        long left = Math.min(firstGid, secondGid);
        long right = Math.max(firstGid, secondGid);
        if (galleriesMapper.selectById(left) == null || galleriesMapper.selectById(right) == null) {
            throw new IllegalArgumentException("待复核画廊不存在");
        }
        AiUseCaseConfigEntity config = configurationService.requireUseCase(AiConfigurationService.VISUAL_REVIEW);
        if (!"EMBEDDING_ONLY".equals(config.getAnalysisMode())) {
            if (config.getProviderId() == null) throw new IllegalStateException("视觉复核尚未配置 LLM 供应商");
            AiProviderConfigEntity provider = configurationService.requireProvider(config.getProviderId());
            if (!Boolean.TRUE.equals(provider.getEnabled())) throw new IllegalStateException("所选 LLM 供应商已禁用");
            if (config.getModel() == null || config.getModel().isBlank()) throw new IllegalStateException("视觉复核尚未选择模型");
            if ("VISION_LLM".equals(config.getAnalysisMode())
                    && Boolean.TRUE.equals(config.getAllowImageTransmission())
                    && !Boolean.TRUE.equals(provider.getAllowVisualInput())) {
                throw new IllegalStateException("所选供应商未允许视觉输入");
            }
        }

        AiVisualReviewJobEntity job = new AiVisualReviewJobEntity();
        job.setId(UUID.randomUUID().toString());
        job.setLeftGid(left);
        job.setRightGid(right);
        job.setStatus("QUEUED");
        job.setAnalysisMode(config.getAnalysisMode());
        job.setProviderId(config.getProviderId());
        job.setModel(config.getModel());
        job.setPromptVersionId(config.getPromptVersionId());
        job.setAllowImageTransmission(Boolean.TRUE.equals(config.getAllowImageTransmission()));
        job.setIncludeMetadata(Boolean.TRUE.equals(config.getIncludeMetadata()));
        job.setSamplePageCount(config.getSamplePageCount());
        job.setAmbiguousMinSimilarity(config.getAmbiguousMinSimilarity());
        job.setHighSimilarity(config.getHighSimilarity());
        jobMapper.insert(job);
        return job;
    }

    /** Reuse an unfinished pair review so refreshing an audit case never sends duplicate requests. */
    @Transactional
    public AiVisualReviewJobEntity createOrReuse(long firstGid, long secondGid) {
        long left = Math.min(firstGid, secondGid);
        long right = Math.max(firstGid, secondGid);
        AiVisualReviewJobEntity active = jobMapper.selectOne(new QueryWrapper<AiVisualReviewJobEntity>()
                .eq("left_gid", left).eq("right_gid", right)
                .in("status", "QUEUED", "RUNNING")
                .orderByDesc("created_at").last("LIMIT 1"));
        return active == null ? create(left, right) : active;
    }

    public AiVisualReviewJobEntity launch(long leftGid, long rightGid) {
        AiVisualReviewJobEntity job = createOrReuse(leftGid, rightGid);
        if (job.getWorkflowId() != null && !job.getWorkflowId().isBlank()) return job;
        try {
            String workflowId = "visual-review-" + job.getId();
            VisualReviewWorkflow workflow = workflowClient.newWorkflowStub(VisualReviewWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE).setWorkflowId(workflowId).build());
            WorkflowExecution execution = WorkflowClient.start(workflow::execute, job.getId());
            attachWorkflow(job.getId(), execution.getWorkflowId());
            job.setWorkflowId(execution.getWorkflowId());
            return job;
        } catch (RuntimeException exception) {
            markLaunchFailed(job.getId(), exception);
            throw exception;
        }
    }
    public void attachWorkflow(String jobId, String workflowId) {
        AiVisualReviewJobEntity job = require(jobId);
        job.setWorkflowId(workflowId);
        jobMapper.updateById(job);
    }

    public void markLaunchFailed(String jobId, Throwable failure) {
        AiVisualReviewJobEntity job = require(jobId);
        job.setStatus("FAILED");
        String message = failure == null || failure.getMessage() == null
                ? "Temporal 工作流启动失败" : failure.getMessage();
        job.setLastError(message.length() <= 950 ? message : message.substring(0, 950));
        job.setFinishedAt(new Date());
        jobMapper.updateById(job);
    }

    public AiVisualReviewJobEntity require(String jobId) {
        AiVisualReviewJobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("视觉复核任务不存在");
        return job;
    }

    public Map<String, Object> view(String jobId) {
        AiVisualReviewJobEntity job = require(jobId);
        AiVisualReviewResultEntity result = resultMapper.selectOne(
                new QueryWrapper<AiVisualReviewResultEntity>().eq("job_id", jobId));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("job", job);
        view.put("result", result);
        return view;
    }
}
