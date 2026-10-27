package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 定义工作流发送结果通知的入口，实际发送由服务端通知处理完成。
 */
@ActivityInterface
public interface NotificationActivity {

    /**
     * 发送 HTML 格式的邮件通知
     *
     * @param subject 邮件主题
     * @param content 邮件正文（支持换行符，内部会转换为 HTML）
     */
    @ActivityMethod
    void sendEmailAlert(String subject, String content);
}
