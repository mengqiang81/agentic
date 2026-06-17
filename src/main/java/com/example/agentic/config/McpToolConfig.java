package com.example.agentic.config;

import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具配置。
 * <p>
 * 动态注册通过 REST API (McpToolController) 完成：
 * POST /api/tools/mcp — 热插拔 MCP Server。
 * <p>
 * 如需启动时静态注册，可在此处声明 McpClientWrapper Bean，
 * 并在 AgentConfig 中注入到 Toolkit。
 */
@Configuration
public class McpToolConfig {
    // 静态注册示例（当有可用的 MCP Server 时启用）：
    // @Bean
    // public McpClientWrapper staticMcpClient() {
    //     return McpClientBuilder.create("static-server")
    //         .sseTransport("http://localhost:3000/mcp")
    //         .buildSync();
    // }
}
