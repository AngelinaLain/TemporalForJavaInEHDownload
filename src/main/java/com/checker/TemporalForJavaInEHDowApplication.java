package com.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TemporalForJavaInEHDowApplication {
    public static void main(String[] args) {
        // Java 8u111+ 默认禁用了 HTTPS CONNECT 隧道的 Basic 代理认证
        // 必须在 Spring 启动之前清除这个限制，否则带认证的 HTTP 代理无法穿透 HTTPS
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        SpringApplication.run(TemporalForJavaInEHDowApplication.class, args);
    }
}
