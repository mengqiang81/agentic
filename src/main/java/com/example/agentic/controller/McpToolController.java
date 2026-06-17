package com.example.agentic.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 工具动态注册 REST API。
 * <p>
 * 支持运行时热插拔 MCP Server 连接。注册后的 MCP 工具自动可被 HarnessAgent 在对话中调用。
 * <p>
 * 支持三种 transport：
 * - sse: Server-Sent Events（旧版 MCP 协议）
 * - streamable-http: Streamable HTTP（新版 MCP 协议）
 * - stdio: 本地进程（需提供 command + args）
 */
@RestController
@RequestMapping("/api/tools/mcp")
public class McpToolController {

    private final Toolkit toolkit;
    /** 已注册的 MCP Server 名称 → transport 描述（Toolkit 未公开 getMcpClientNames，此处本地跟踪） */
    private final Map<String, String> registered = new ConcurrentHashMap<>();

    public McpToolController(HarnessAgent harnessAgent) {
        this.toolkit = harnessAgent.getToolkit();
    }

    /**
     * 动态注册 MCP Server。
     * <pre>
     * POST /api/tools/mcp
     * {
     *   "name": "my-mcp-server",
     *   "transport": "sse",           // sse | streamable-http | stdio
     *   "url": "http://localhost:3000/mcp",  // sse/streamable-http 必填
     *   "command": "npx",                    // stdio 必填
     *   "args": ["-y", "@mcp/server"],       // stdio 可选
     *   "headers": {"Authorization": "Bearer xxx"}  // 可选 headers
     * }
     * </pre>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> registerMcpServer(@RequestBody JsonNode body) {
        String name = requireField(body, "name");
        String transport = requireField(body, "transport");

        McpClientBuilder builder = McpClientBuilder.create(name);

        // 配置 transport
        switch (transport) {
            case "sse" -> {
                String url = requireField(body, "url");
                builder.sseTransport(url);
            }
            case "streamable-http" -> {
                String url = requireField(body, "url");
                builder.streamableHttpTransport(url);
            }
            case "stdio" -> {
                String command = requireField(body, "command");
                List<String> args = parseStringList(body.get("args"));
                builder.stdioTransport(command, args.toArray(new String[0]));
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported transport: " + transport + ". Supported: sse, streamable-http, stdio");
        }

        // 可选 headers
        JsonNode headersNode = body.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry ->
                    builder.header(entry.getKey(), entry.getValue().asText()));
        }

        // 异步构建 McpClientWrapper → 注册到 Toolkit
        return builder.buildAsync()
                .flatMap(toolkit::registerMcpClient)
                .doOnSuccess(v -> registered.put(name, transport))
                .thenReturn(Map.<String, Object>of(
                        "status", "registered",
                        "name", name,
                        "transport", transport
                ))
                .onErrorMap(ex -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Failed to connect MCP server '" + name + "': " + ex.getMessage(), ex));
    }

    /**
     * 列出已注册的 MCP Server。
     * <p>
     * 同时返回当前 toolkit 中所有工具名（含内置 + MCP），便于调试。
     */
    @GetMapping
    public Mono<Map<String, Object>> listMcpServers() {
        return Mono.fromCallable(() -> {
            List<String> allTools = new ArrayList<>(toolkit.getToolNames());
            Collections.sort(allTools);
            return Map.<String, Object>of(
                    "servers", registered,
                    "allTools", allTools
            );
        });
    }

    /**
     * 断开并移除 MCP Server（按注册时的 name）
     */
    @DeleteMapping("/{name}")
    public Mono<Map<String, String>> unregisterMcpServer(@PathVariable String name) {
        if (!registered.containsKey(name)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "MCP server '" + name + "' not found"));
        }
        return toolkit.removeMcpClient(name)
                .doOnSuccess(v -> registered.remove(name))
                .thenReturn(Map.of("status", "unregistered", "name", name));
    }

    // ==================== 工具方法 ====================

    private String requireField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing required field: " + field);
        }
        return node.asText();
    }

    private List<String> parseStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        arrayNode.forEach(n -> list.add(n.asText()));
        return list;
    }
}
