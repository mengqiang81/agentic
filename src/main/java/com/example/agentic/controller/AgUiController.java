package com.example.agentic.controller;

import com.example.agentic.agent.AgentSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AG-UI 协议端点。
 * <p>
 * 实现标准 AG-UI HTTP SSE 端点：
 * POST /awp/v1/runs
 * Content-Type: application/json
 * Accept: text/event-stream
 * <p>
 * 多租户来源：从 X-Tenant-Id、X-User-Id Header 提取，构建 RuntimeContext。
 */
@RestController
@RequestMapping("/awp/v1")
public class AgUiController {

    private final AgentSessionService agentSessionService;

    public AgUiController(AgentSessionService agentSessionService) {
        this.agentSessionService = agentSessionService;
    }

    /**
     * AG-UI 标准运行端点。
     * <p>
     * Request Body (AG-UI RunAgentInput):
     * {
     *   "thread_id": "session-xxx",
     *   "run_id": "run-xxx",
     *   "messages": [{"role":"user","content":"hello"}],
     *   "state": {}
     * }
     */
    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runAgent(
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {

        String threadId = body.has("thread_id") ? body.get("thread_id").asText() : "default-session";
        String runId = body.has("run_id") ? body.get("run_id").asText() : java.util.UUID.randomUUID().toString();

        // 从 messages 数组中提取最后一条 user 消息
        String userMessage = extractLastUserMessage(body);

        return agentSessionService.streamAgentEvents(tenantId, userId, threadId, userMessage);
    }

    /**
     * 提取 messages 数组中最后一条 role=user 的消息内容
     */
    private String extractLastUserMessage(JsonNode body) {
        if (!body.has("messages") || !body.get("messages").isArray()) {
            return "";
        }
        JsonNode messages = body.get("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode msg = messages.get(i);
            if ("user".equals(msg.path("role").asText())) {
                return msg.path("content").asText("");
            }
        }
        return "";
    }
}
