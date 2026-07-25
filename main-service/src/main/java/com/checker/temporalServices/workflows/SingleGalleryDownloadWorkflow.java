package com.checker.temporalServices.workflows;

import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 单本画廊下载子工作流：封装 [提取直链 → 推送群晖 → 轮询等待 → Komga 入库] 全流程。
 * <p>
 * 由主工作流通过 ChildWorkflow 方式派发，每个子工作流拥有独立的事件历史，
 * 彻底解决串行轮询导致的 Temporal 历史记录爆炸问题。
 */
@WorkflowInterface
public interface SingleGalleryDownloadWorkflow {

    /**
     * 处理单本画廊的完整下载/入库生命周期
     *
     * @param gallery        画廊实体
     * @param compensateOnly true 时跳过下载，仅执行 Komga 补偿入库
     * @param settings       工作流运行时配置（来自 application.yaml）
     */
    @WorkflowMethod
    void processSingleGallery(EhGalleriesEntity gallery, boolean compensateOnly, WorkflowSettings settings);
}
