package com.checker.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Temporal beans are now managed by temporal-spring-boot-starter auto-configuration.
// Properties: spring.temporal.connection.target / spring.temporal.namespace (in application.yaml under spring:)
// @Configuration  <-- intentionally disabled to avoid conflicting with auto-config
public class TemporalClientConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.connection.target:127.0.0.1:7233}") String target
    ) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target)
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    public WorkflowClient workflowClient(
            WorkflowServiceStubs workflowServiceStubs,
            @Value("${temporal.namespace:default}") String namespace
    ) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build();
        return WorkflowClient.newInstance(workflowServiceStubs, options);
    }
}
