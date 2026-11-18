package com.checker.temporal.workflow;

import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** 供主服务请求抓取节点读取单个画廊的标签或封面指纹。 */
@WorkflowInterface
public interface GallerySourceWorkflow {
    @WorkflowMethod
    GallerySourceResult collect(GallerySourceRequest request);
}
