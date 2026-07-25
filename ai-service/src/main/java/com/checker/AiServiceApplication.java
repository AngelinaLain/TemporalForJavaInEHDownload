package com.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Hello world!
 *
 */
@EnableDiscoveryClient
@SpringBootApplication
public class AiServiceApplication {
    public static void main( String[] args ) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
