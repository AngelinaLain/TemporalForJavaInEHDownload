package com.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot 启动类，EHentai 自动化下载工作流应用程序入口
 */
@SpringBootApplication
@EnableAsync
public class MainServiceApplication {

    /**
     * 应用程序主入口，启动前清除 JDK 对 HTTPS 隧道代理认证的限制
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // Java 8u111+ 默认禁用了 HTTPS CONNECT 隧道的 Basic 代理认证
        // 必须在 Spring 启动之前清除这个限制，否则带认证的 HTTP 代理无法穿透 HTTPS
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        SpringApplication.run(MainServiceApplication.class, args);
    }
}
