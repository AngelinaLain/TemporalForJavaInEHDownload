package com.checker.service.workflow.rss;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
@WorkflowInterface
public interface RssTickWorkflow { @WorkflowMethod void tick(); }
