package com.checker.temporalServices.workflows;

import com.checker.entity.EhGalleriesEntity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 定义下载后等待 Komga 识别文件并确认入库的子流程。
 */
@WorkflowInterface
public interface KomgaImportWorkflow {

    /**
     * 按传入次数和间隔等待图库识别，确认后推送元数据，超时则记录失败原因。
     * */
    @WorkflowMethod
    void waitForImport(EhGalleriesEntity gallery, int maxRetries, int pollIntervalSeconds, String timeoutSubject, String timeoutContent);
}
