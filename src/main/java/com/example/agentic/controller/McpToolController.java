package com.example.agentic.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具动态注册 REST API。
 * <p>
 * 支持运行时热插拔 MCP Server 连接。
 * 注册后的 MCP 工具可被 HarnessAgent 在对话中调用。
 */
@RestController
@RequestMapping("/api/tools/mcp")
public class McpToolController {

    // 已注册的 MCP 连接记录（transport URL → 状态）
    private final Map<String, String> registeredMcpServers = new ConcurrentHashMap<>();

    /**
     * 动态注册 MCP Server
     * <p>
     * Request Body:
     * { "transport": "sse", "url": "http://localhost:3000/mcp" }
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> registerMcpServer(@RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            String transport = body.get("transport").asText();
            String url = body.get("url").asText();

            // TODO: 实际创建 McpClient 并注册到 HarnessAgent Toolkit
            // McpClient client = McpClientBuilder.builder()
            //     .transport(new SseHttpTransport(url))
            //     .build();
            // toolkit.registerMcpClient(client);

            registeredMcpServers.put(url, "connected");
            return Map.of("status", "registered", "transport", transport, "url", url);
        });
    }

    /**
     * 列出已注册的 MCP Server
     */
    @GetMapping
    public Mono<Map<String, String>> listMcpServers() {
        return Mono.just(registeredMcpServers);
    }

    /**
     * 断开并移除 MCP Server
     */
    @DeleteMapping
    public Mono<Map<String, String>> unregisterMcpServer(@RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            String url = body.get("url").asText();
            registeredMcpServers.remove(url);
            // TODO: 实际断开 McpClient 连接
            return Map.of("status", "unregistered", "url", url);
        });
    }
}
