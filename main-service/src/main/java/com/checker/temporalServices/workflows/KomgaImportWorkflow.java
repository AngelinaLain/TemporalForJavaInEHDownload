package com.checker.temporalServices.workflows;

import com.checker.entity.EhGalleriesEntity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface KomgaImportWorkflow {
    @WorkflowMethod
    void waitForImport(EhGalleriesEntity gallery, int maxRetries, int pollIntervalSeconds, String timeoutSubject, String timeoutContent);
}
