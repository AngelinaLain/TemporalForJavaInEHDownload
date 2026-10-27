package com.checker.temporalServices.workflows;

import com.checker.dto.WorkflowSettings;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 定义单个画廊的下载和入库生命周期。
 * 独立的子流程保存自己的等待与重试历史，减少父流程的事件数量。
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
