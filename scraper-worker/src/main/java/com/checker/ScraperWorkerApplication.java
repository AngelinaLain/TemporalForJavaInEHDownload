package com.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动抓取工作进程，从任务队列接收画廊检索、元数据读取和下载地址提取任务。
 */
@SpringBootApplication
public class ScraperWorkerApplication {

    /** 启动应用并注册抓取任务的处理方法。 */
    public static void main(String[] args) {
        SpringApplication.run(ScraperWorkerApplication.class, args);
    }

}
