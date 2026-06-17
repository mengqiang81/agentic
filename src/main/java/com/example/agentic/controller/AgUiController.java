package com.example.agentic.controller;

import com.example.agentic.agent.AgentSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AG-UI 协议端点。
 * <p>
 * 实现标准 AG-UI HTTP SSE 端点：
 * POST /awp/v1/runs
 * Content-Type: application/json
 * Accept: text/event-stream
 * <p>
 * 支持两种 HITL 恢复方式：
 * 1. AG-UI 标准 resume 字段（推荐）：
 *    {"resume": [{"interruptId":"xxx", "status":"resolved", "payload":{"approved":true}}]}
 * 2. 旧版 confirm_results 字段（deprecated，兼容 Python 测试脚本）
 * <p>
 * 多租户来源：从 X-Tenant-Id、X-User-Id Header 提取，构建 RuntimeContext。
 */
@RestController
@RequestMapping("/awp/v1")
public class AgUiController {

    private final AgentSessionService agentSessionService;
    private final ObjectMapper objectMapper;

    public AgUiController(AgentSessionService agentSessionService, ObjectMapper objectMapper) {
        this.agentSessionService = agentSessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * AG-UI 标准运行端点。
     * <p>
     * Request Body (AG-UI RunAgentInput):
     * {
     *   "thread_id": "session-xxx",   // 或 "threadId"
     *   "run_id": "run-xxx",          // 或 "runId"
     *   "messages": [{"role":"user","content":"hello"}],
     *   "resume": [{"interruptId":"xxx","status":"resolved","payload":{"approved":true}}],
     *   "state": {}
     * }
     */
    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runAgent(
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {

        // 支持 camelCase 和 snake_case 两种字段名
        String threadId = getStringField(body, "threadId", "thread_id", "default-session");
        String runId = getStringField(body, "runId", "run_id", UUID.randomUUID().toString());

        // 从 messages 数组中提取最后一条 user 消息
        String userMessage = extractLastUserMessage(body);

        // 优先解析 AG-UI 标准 resume 字段，若无则 fallback CopilotKit forwardedProps.command 格式，最后兑底旧版 confirm_results
        List<ConfirmResult> confirmResults = extractResume(body);
        if (confirmResults == null) {
            confirmResults = extractCopilotKitResume(body);
        }
        if (confirmResults == null) {
            confirmResults = extractConfirmResults(body);
        }

        return agentSessionService.streamAgentEvents(
                tenantId, userId, threadId, runId, userMessage, confirmResults);
    }

    /**
     * 解析 AG-UI 标准 resume 字段。
     * <p>
     * 格式：[{"interruptId":"<toolCallId>", "status":"resolved"|"cancelled", "payload":{"approved":true|false}}]
     * <p>
     * 映射规则：
     * - status=="resolved" && payload.approved==true → ConfirmResult(true, toolCall)
     * - status=="resolved" && payload.approved==false 或 status=="cancelled" → ConfirmResult(false, toolCall)
     */
    private List<ConfirmResult> extractResume(JsonNode body) {
        if (!body.has("resume") || !body.get("resume").isArray()) {
            return null;
        }
        JsonNode arr = body.get("resume");
        if (arr.isEmpty()) {
            return null;
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (JsonNode item : arr) {
            String interruptId = item.path("interruptId").asText("");
            String status = item.path("status").asText("resolved");
            JsonNode payload = item.path("payload");

            boolean approved;
            if ("cancelled".equals(status)) {
                approved = false;
            } else {
                approved = payload.path("approved").asBoolean(true);
            }

            // interruptId 就是 toolCallId
            // 使用 metadata 中的 tool name（如果有），否则空
            String toolName = payload.path("toolName").asText(
                    item.path("metadata").path("name").asText(""));
            Map<String, Object> input = new HashMap<>();
            if (payload.has("input") && payload.get("input").isObject()) {
                payload.get("input").fields().forEachRemaining(e ->
                        input.put(e.getKey(), e.getValue().asText()));
            }

            // content 字段必须设置为 input 的 JSON 字符串，agentscope 执行工具时依赖此字段
            String content;
            try {
                content = objectMapper.writeValueAsString(input);
            } catch (Exception e) {
                content = "{}";
            }
            ToolUseBlock toolCall = new ToolUseBlock(interruptId, toolName, input, content, null);
            results.add(new ConfirmResult(approved, toolCall));
        }
        return results.isEmpty() ? null : results;
    }

    /**
     * 解析 CopilotKit forwardedProps.command 格式的 resume。
     * <p>
     * CopilotKit useInterrupt/useLangGraphInterrupt 在 resolve 时发送：
     * {
     *   "forwardedProps": {
     *     "command": {
     *       "resume": "{\"approved\":true}",
     *       "interruptEvent": [...]  // 原始 interrupt 事件数据
     *     }
     *   }
     * }
     */
    private List<ConfirmResult> extractCopilotKitResume(JsonNode body) {
        JsonNode command = body.path("forwardedProps").path("command");
        if (command.isMissingNode()) {
            return null;
        }
        JsonNode resumeNode = command.path("resume");
        if (resumeNode.isMissingNode()) {
            return null;
        }

        // 解析用户响应（resume 可能是 JSON 字符串或对象）
        boolean approved = true;
        if (resumeNode.isTextual()) {
            try {
                JsonNode parsed = objectMapper.readTree(resumeNode.asText());
                approved = parsed.path("approved").asBoolean(true);
            } catch (Exception ignored) {
                // 非 JSON 字符串，默认批准
            }
        } else if (resumeNode.isObject()) {
            approved = resumeNode.path("approved").asBoolean(true);
        }

        // interruptEvent 包含原始 interrupt 数据（toolCallId, name 等）
        JsonNode interruptEvent = command.path("interruptEvent");
        List<ConfirmResult> results = new ArrayList<>();

        if (interruptEvent.isArray() && !interruptEvent.isEmpty()) {
            for (JsonNode item : interruptEvent) {
                String toolCallId = item.path("toolCallId").asText(item.path("id").asText(""));
                String toolName = item.path("metadata").path("name").asText("");
                // 正确保留嵌套对象结构（不能用 .asText() 扁平化）
                Map<String, Object> input;
                JsonNode inputNode = item.path("metadata").path("input");
                if (inputNode.isObject()) {
                    input = objectMapper.convertValue(inputNode, new TypeReference<Map<String, Object>>() {});
                } else {
                    input = new HashMap<>();
                }
                // content 字段必须设置为 input 的 JSON 字符串，agentscope 执行工具时依赖此字段
                String content;
                try {
                    content = objectMapper.writeValueAsString(input);
                } catch (Exception e) {
                    content = "{}";
                }
                ToolUseBlock toolCall = new ToolUseBlock(toolCallId, toolName, input, content, null);
                results.add(new ConfirmResult(approved, toolCall));
            }
        } else {
            // 没有 interruptEvent 时，用空 toolCall 兜底
            ToolUseBlock toolCall = new ToolUseBlock("", "", new HashMap<>(), "{}", null);
            results.add(new ConfirmResult(approved, toolCall));
        }

        return results.isEmpty() ? null : results;
    }

    /**
     * 从 confirm_results 数组提取 HITL 确认决定（deprecated，兼容旧客户端）。
     * <p>
     * 格式：[{"tool_call_id":"xxx", "tool_name":"bgm_ai_model_query", "confirmed":true, "input":{...}}]
     */
    private List<ConfirmResult> extractConfirmResults(JsonNode body) {
        if (!body.has("confirm_results") || !body.get("confirm_results").isArray()) {
            return null;
        }
        JsonNode arr = body.get("confirm_results");
        List<ConfirmResult> results = new ArrayList<>();
        for (JsonNode item : arr) {
            String toolCallId = item.path("tool_call_id").asText("");
            String toolName = item.path("tool_name").asText("");
            boolean confirmed = item.path("confirmed").asBoolean(true);

            // 正确保留嵌套对象结构（不能用 .asText() 扁平化）
            Map<String, Object> input;
            if (item.has("input") && item.get("input").isObject()) {
                input = objectMapper.convertValue(item.get("input"), new TypeReference<Map<String, Object>>() {});
            } else {
                input = new HashMap<>();
            }
            // content 字段必须设置为 input 的 JSON 字符串，agentscope 执行工具时依赖此字段
            String content;
            try {
                content = objectMapper.writeValueAsString(input);
            } catch (Exception e) {
                content = "{}";
            }
            ToolUseBlock toolCall = new ToolUseBlock(toolCallId, toolName, input, content, null);
            results.add(new ConfirmResult(confirmed, toolCall));
        }
        return results.isEmpty() ? null : results;
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

    /**
     * 获取 JSON 字段，支持两个候选字段名（camelCase 优先，snake_case 兜底）
     */
    private String getStringField(JsonNode body, String camelCase, String snakeCase, String defaultValue) {
        if (body.has(camelCase)) {
            return body.get(camelCase).asText(defaultValue);
        }
        if (body.has(snakeCase)) {
            return body.get(snakeCase).asText(defaultValue);
        }
        return defaultValue;
    }
}
