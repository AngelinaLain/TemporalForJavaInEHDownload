package com.checker.temporalServices.workflows;

import com.checker.dto.SearchOptions;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EHAutomationWorkflow {
    /**
     * 工作流的入口方法（对标 JHenTai SearchConfig）
     * @param searchOptions 包含所有搜索参数的对象
     */
    @WorkflowMethod
    void executeAutomation(SearchOptions searchOptions);
}
