package com.example.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 通用智能体平台启动入口。
 * <p>
 * 技术栈：
 * - Spring AI Alibaba 1.1.2.0（传统 Spring AI 写法兼容）
 * - AgentScope HarnessAgent 2.0.0-RC3（智能体核心）
 * - Spring Boot WebFlux（SSE 流式输出）
 * - Redis（多租户 Session 持久化）
 * - OTEL（全链路 Tracing）
 * <p>
 * 注：DashScope starter 启动时强制要求 api-key，本项目不使用 DashScope（用 DeepSeek），
 * 在 application.yml 中填了 dummy api-key 以跳过校验。
 */
@SpringBootApplication
public class AgenticApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticApplication.class, args);
    }
}
