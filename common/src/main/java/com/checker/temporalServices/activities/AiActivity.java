package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/** 定义 AI 服务执行的任务，主工作流通过这个接口请求生成画廊简介。 */
@ActivityInterface
public interface AiActivity {
    /** 根据标题与标签生成中文简介，具体模型调用在 AI 服务中执行。 */
    @ActivityMethod
    String generateGallerySummary(String title, List<String> tags);
}
