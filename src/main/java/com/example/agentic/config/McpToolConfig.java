package com.example.agentic.config;

import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置。
 * <p>
 * 支持两种注册方式：
 * 1. 静态注册（启动时）：通过配置文件定义 MCP Server 地址，启动时自动连接
 * 2. 动态注册（运行时）：通过 REST API POST /api/tools/mcp 热插拔
 * <p>
 * MCP 工具注册到 HarnessAgent Toolkit 后，agent 可在对话中自动调用。
 */
@Configuration
public class McpToolConfig {

    // TODO: 静态 MCP 注册示例（当有可用的 MCP Server 时启用）
    // @Bean
    // public McpClient mcpClient() {
    //     return McpClientBuilder.builder()
    //         .transport(new SseHttpTransport("http://localhost:3000/mcp"))
    //         .build();
    // }
}
