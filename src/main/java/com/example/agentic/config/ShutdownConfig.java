package com.example.agentic.config;

import org.springframework.context.annotation.Configuration;

/**
 * 优雅停机配置。
 * <p>
 * GracefulShutdownManager 默认注册 JVM hook，
 * 接好 SIGTERM，等待 inflight 请求完成。
 * <p>
 * Spring Boot 的 server.shutdown=graceful 已在 application.yml 中配置。
 * 此配置类用于扩展：如自定义 inflight 等待时间、注册额外的 shutdown hook 等。
 */
@Configuration
public class ShutdownConfig {

    // Spring Boot 3.x 已内置优雅停机支持（server.shutdown=graceful）
    // AgentScope 的 GracefulShutdownManager 默认注册 JVM hook
    // 如需自定义 inflight 等待时间，可在此注入 GracefulShutdownManager 并调用 setConfig(...)
}
