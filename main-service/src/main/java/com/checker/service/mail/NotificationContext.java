package com.checker.service.mail;

/** 记录通知触发时的工作流身份与时间，供异步发信时展示，避免把发送时间误当任务结束时间。 */
public record NotificationContext(String workflowId, String runId, long triggeredAt) {
    public static NotificationContext now() {
        return new NotificationContext(null, null, System.currentTimeMillis());
    }
}
