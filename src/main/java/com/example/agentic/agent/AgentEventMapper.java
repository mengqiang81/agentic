package com.example.agentic.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AgentEvent → AG-UI 标准事件转换器（工厂）。
 * <p>
 * 每次 run 创建一个有状态的 {@link RunMapper}，追踪 per-run 上下文：
 * - threadId / runId（从请求中获取）
 * - 是否已发送 TEXT_MESSAGE_START
 * - 待处理的 interrupt 列表（RequireUserConfirmEvent 缓冲）
 * <p>
 * AG-UI 标准要求：
 * - 所有字段 camelCase
 * - SSE 只发 data: 行（不发 event: 行），type 包含在 JSON 内
 * - TEXT_MESSAGE_START 在首个 TEXT_MESSAGE_CONTENT 之前发送
 * - TOOL_CALL_ARGS 紧跟 TOOL_CALL_START 发送（完整 input JSON）
 * - 被中断的工具不发 TOOL_CALL_* 事件，仅通过 CUSTOM on_interrupt 传递信息
 * - 正常执行的工具 flush 完整 START → ARGS → END → RESULT 生命周期
 */
@Component
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    public AgentEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一个 per-run 有状态映射器。
     */
    public RunMapper createRunMapper(String threadId, String runId) {
        return new RunMapper(threadId, runId);
    }

    /**
     * Per-run 有状态映射器。
     * 非线程安全 — 每个 run 使用独立实例，在单线程 Flux 链中调用。
     *
     * <p>关键设计：Tool Call 事件缓冲机制
     * agentscope 的事件顺序是：ToolCallStart → ToolCallDelta → ToolCallEnd → (RequireUserConfirmEvent | ToolResult)
     * 我们无法在 ToolCallStart 时判断该工具是否会被中断。因此：
     * - 缓冲所有 ToolCallStart/Delta/End 事件
     * - 当 ToolResultTextDelta 或 ToolResultEnd 到达时 → 该工具正常执行，flush 缓冲
     * - 当 RequireUserConfirmEvent 到达时 → 该工具被中断，丢弃缓冲，仅发 CUSTOM 中断事件
     * 这确保前端不会看到"工具正在执行→再弹确认框"的错误顺序。
     */
    public class RunMapper {

        private final String threadId;
        private final String runId;
        private boolean textMessageStarted = false;
        private String currentMessageId = null;
        private final List<ObjectNode> pendingInterrupts = new ArrayList<>();
        // 工具结果内容缓冲区：toolCallId → 累积的文本内容
        private final Map<String, StringBuilder> toolResultBuffers = new HashMap<>();
        // Tool Call 事件缓冲区：toolCallId → 该工具的 AG-UI 事件列表
        // 事件在确认工具正常执行后才 flush 到前端
        private final Map<String, List<AgUiEvent>> pendingToolCalls = new HashMap<>();

        // Text delta 合并缓冲：减少前端事件数量，避免 AG-UI 开发模式下
        // structuredClone + Object.freeze 的 O(n²) 性能退化
        private final StringBuilder textDeltaBuffer = new StringBuilder();
        private static final int TEXT_BUFFER_FLUSH_THRESHOLD = 20;

        RunMapper(String threadId, String runId) {
            this.threadId = threadId;
            this.runId = runId;
        }

        /**
         * 将 AgentEvent 映射为 0~N 个 AG-UI 事件。
         *
         * @param event AgentScope 内部事件
         * @return AG-UI 事件列表（可能为空）
         */
        public List<AgUiEvent> map(AgentEvent event) {
            if (event instanceof AgentStartEvent) {
                return mapRunStarted();
            }
            if (event instanceof TextBlockDeltaEvent e) {
                return mapTextDelta(e);
            }
            if (event instanceof TextBlockEndEvent e) {
                return mapTextEnd(e);
            }
            if (event instanceof ToolCallStartEvent e) {
                return mapToolCallStart(e);
            }
            if (event instanceof ToolCallDeltaEvent e) {
                return mapToolCallArgs(e);
            }
            if (event instanceof ToolCallEndEvent e) {
                return mapToolCallEnd(e);
            }
            if (event instanceof ToolResultTextDeltaEvent e) {
                return bufferToolResultText(e); // flush tool call buffer + 累积结果文本
            }
            if (event instanceof ToolResultEndEvent e) {
                return mapToolResult(e);
            }
            if (event instanceof RequireUserConfirmEvent e) {
                return handleInterrupt(e);
            }
            if (event instanceof AgentEndEvent) {
                return mapRunFinished();
            }
            // 其余内部事件忽略
            return Collections.emptyList();
        }

        // --- RUN_STARTED ---
        private List<AgUiEvent> mapRunStarted() {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "RUN_STARTED");
            data.put("threadId", threadId);
            data.put("runId", runId);
            return List.of(new AgUiEvent("RUN_STARTED", data.toString()));
        }

        // --- TEXT_MESSAGE_START + TEXT_MESSAGE_CONTENT（带合并缓冲）---
        private List<AgUiEvent> mapTextDelta(TextBlockDeltaEvent e) {
            List<AgUiEvent> events = new ArrayList<>(2);
            if (!textMessageStarted) {
                textMessageStarted = true;
                currentMessageId = safe(e.getBlockId());
                if (currentMessageId.isEmpty()) {
                    currentMessageId = UUID.randomUUID().toString();
                }
                ObjectNode startData = objectMapper.createObjectNode();
                startData.put("type", "TEXT_MESSAGE_START");
                startData.put("messageId", currentMessageId);
                startData.put("role", "assistant");
                events.add(new AgUiEvent("TEXT_MESSAGE_START", startData.toString()));
            }
            // 合并 text delta 到缓冲区
            textDeltaBuffer.append(safe(e.getDelta()));
            // 达到阈值时 flush
            if (textDeltaBuffer.length() >= TEXT_BUFFER_FLUSH_THRESHOLD) {
                events.addAll(flushTextDeltaBuffer());
            }
            return events;
        }

        // --- flush text delta 缓冲 → 单个 TEXT_MESSAGE_CONTENT 事件 ---
        private List<AgUiEvent> flushTextDeltaBuffer() {
            if (textDeltaBuffer.isEmpty()) {
                return Collections.emptyList();
            }
            ObjectNode contentData = objectMapper.createObjectNode();
            contentData.put("type", "TEXT_MESSAGE_CONTENT");
            contentData.put("messageId", currentMessageId);
            contentData.put("delta", textDeltaBuffer.toString());
            textDeltaBuffer.setLength(0);
            return List.of(new AgUiEvent("TEXT_MESSAGE_CONTENT", contentData.toString()));
        }

        // --- TEXT_MESSAGE_END ---
        private List<AgUiEvent> mapTextEnd(TextBlockEndEvent e) {
            if (!textMessageStarted) {
                return Collections.emptyList();
            }
            textMessageStarted = false;
            List<AgUiEvent> events = new ArrayList<>(2);
            // flush 剩余缓冲的 text delta
            events.addAll(flushTextDeltaBuffer());
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TEXT_MESSAGE_END");
            data.put("messageId", currentMessageId);
            events.add(new AgUiEvent("TEXT_MESSAGE_END", data.toString()));
            return events;
        }

        // --- TOOL_CALL_START (缓冲，不立即下发) ---
        // 缓冲 tool call 事件，等确认该工具正常执行后再 flush。
        // 如果是被中断的工具，缓冲将被丢弃。
        private List<AgUiEvent> mapToolCallStart(ToolCallStartEvent e) {
            List<AgUiEvent> immediateEvents = new ArrayList<>(2);

            // 如果有未关闭的 text message，先 flush 缓冲再发 TEXT_MESSAGE_END
            if (textMessageStarted) {
                textMessageStarted = false;
                immediateEvents.addAll(flushTextDeltaBuffer());
                ObjectNode endData = objectMapper.createObjectNode();
                endData.put("type", "TEXT_MESSAGE_END");
                endData.put("messageId", currentMessageId);
                immediateEvents.add(new AgUiEvent("TEXT_MESSAGE_END", endData.toString()));
            }

            // 缓冲 TOOL_CALL_START 事件
            String toolCallId = safe(e.getToolCallId());
            ObjectNode startData = objectMapper.createObjectNode();
            startData.put("type", "TOOL_CALL_START");
            startData.put("toolCallId", toolCallId);
            startData.put("toolCallName", safe(e.getToolCallName()));
            pendingToolCalls.computeIfAbsent(toolCallId, k -> new ArrayList<>())
                    .add(new AgUiEvent("TOOL_CALL_START", startData.toString()));

            return immediateEvents;
        }

        // --- TOOL_CALL_ARGS (缓冲) ---
        private List<AgUiEvent> mapToolCallArgs(ToolCallDeltaEvent e) {
            String toolCallId = safe(e.getToolCallId());
            ObjectNode argsData = objectMapper.createObjectNode();
            argsData.put("type", "TOOL_CALL_ARGS");
            argsData.put("toolCallId", toolCallId);
            argsData.put("delta", safe(e.getDelta()));
            pendingToolCalls.computeIfAbsent(toolCallId, k -> new ArrayList<>())
                    .add(new AgUiEvent("TOOL_CALL_ARGS", argsData.toString()));
            return Collections.emptyList();
        }

        // --- TOOL_CALL_END (缓冲) ---
        private List<AgUiEvent> mapToolCallEnd(ToolCallEndEvent e) {
            String toolCallId = safe(e.getToolCallId());
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TOOL_CALL_END");
            data.put("toolCallId", toolCallId);
            pendingToolCalls.computeIfAbsent(toolCallId, k -> new ArrayList<>())
                    .add(new AgUiEvent("TOOL_CALL_END", data.toString()));
            return Collections.emptyList();
        }

        // --- flush 缓冲的 tool call 事件（工具正常执行时调用） ---
        private List<AgUiEvent> flushToolCallBuffer(String toolCallId) {
            List<AgUiEvent> buffered = pendingToolCalls.remove(toolCallId);
            return buffered != null ? buffered : Collections.emptyList();
        }

        // --- 累积工具结果文本 (ToolResultTextDeltaEvent) ---
        // 收到 ToolResultTextDelta 说明该工具正在正常执行 → flush 缓冲的 tool call 事件
        private List<AgUiEvent> bufferToolResultText(ToolResultTextDeltaEvent e) {
            String toolCallId = safe(e.getToolCallId());
            // 首次收到该工具的结果文本时，flush 缓冲的 START/ARGS/END
            List<AgUiEvent> flushed = flushToolCallBuffer(toolCallId);
            // 累积结果文本
            toolResultBuffers
                    .computeIfAbsent(toolCallId, k -> new StringBuilder())
                    .append(safe(e.getDelta()));
            return flushed;
        }

        // --- TOOL_CALL_RESULT ---
        // AG-UI schema 要求 TOOL_CALL_RESULT 必须包含 messageId 字段
        // 使用缓冲的文本内容作为 content（来自 ToolResultTextDeltaEvent），
        // 如果没有文本内容则 fallback 到 state 枚举名
        private List<AgUiEvent> mapToolResult(ToolResultEndEvent e) {
            String toolCallId = safe(e.getToolCallId());
            // 确保缓冲的 tool call 事件已 flush（如果没有 ToolResultTextDelta 的情况）
            List<AgUiEvent> events = new ArrayList<>(flushToolCallBuffer(toolCallId));
            // 取出缓冲的真实工具输出
            StringBuilder buf = toolResultBuffers.remove(toolCallId);
            String content;
            if (buf != null && !buf.isEmpty()) {
                content = buf.toString();
            } else {
                // 无文本输出时使用状态枚举名（如 SUCCESS/ERROR/DENIED）
                content = e.getState() != null ? e.getState().toString() : "";
            }

            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TOOL_CALL_RESULT");
            data.put("messageId", "result-" + toolCallId);
            data.put("toolCallId", toolCallId);
            data.put("role", "tool");
            data.put("content", content);
            events.add(new AgUiEvent("TOOL_CALL_RESULT", data.toString()));
            return events;
        }

        // --- 处理中断：丢弃被中断工具的缓冲事件 + 缓冲 interrupt 信息 ---
        // 被中断的工具不应发送任何 TOOL_CALL_* 事件到前端（它们还没执行）。
        // 工具信息已包含在 CUSTOM on_interrupt 事件的 metadata 中。
        private List<AgUiEvent> handleInterrupt(RequireUserConfirmEvent e) {
            for (ToolUseBlock tc : e.getToolCalls()) {
                // 1) 丢弃该工具缓冲的 TOOL_CALL_START/ARGS/END 事件
                pendingToolCalls.remove(tc.getId());

                // 2) 缓冲 interrupt 元信息（合入后续 CUSTOM on_interrupt 事件）
                ObjectNode interrupt = objectMapper.createObjectNode();
                interrupt.put("id", tc.getId());
                interrupt.put("reason", "tool_call");
                interrupt.put("toolCallId", tc.getId());
                interrupt.put("message", "Approve calling tool " + tc.getName() + "?");
                ObjectNode metadata = objectMapper.createObjectNode();
                metadata.put("name", tc.getName());
                metadata.putPOJO("input", tc.getInput());
                interrupt.set("metadata", metadata);
                pendingInterrupts.add(interrupt);
            }
            return Collections.emptyList();
        }

        // --- RUN_FINISHED (possibly with interrupts) ---
        // 当有 pending interrupts 时：
        //   1) 发 CUSTOM "on_interrupt" 事件（CopilotKit useInterrupt hook 的触发源）
        //   2) RUN_FINISHED 不带 outcome.type="interrupt"，避免 AbstractAgent 填充
        //      pendingInterrupts，否则 CopilotKit resume 时会因缺少标准 resume 字段
        //      而在 onInitialize 中抛出 "pending interrupt(s) not addressed by resume" 错误。
        //
        // 原理：AG-UI 标准用 RUN_FINISHED.outcome.interrupt + resume 字段处理中断，
        //       但 CopilotKit 用 CUSTOM 事件 + forwardedProps.command.resume 处理，二者冲突。
        //       此处选择兼容 CopilotKit 方案，只通过 CUSTOM 事件传递中断信息。
        private List<AgUiEvent> mapRunFinished() {
            List<AgUiEvent> events = new ArrayList<>(2);

            // 如果有未关闭的 text message，先 flush 缓冲再关闭
            if (textMessageStarted) {
                textMessageStarted = false;
                events.addAll(flushTextDeltaBuffer());
                ObjectNode endData = objectMapper.createObjectNode();
                endData.put("type", "TEXT_MESSAGE_END");
                endData.put("messageId", currentMessageId);
                events.add(new AgUiEvent("TEXT_MESSAGE_END", endData.toString()));
            }

            if (!pendingInterrupts.isEmpty()) {
                // CUSTOM event — CopilotKit useInterrupt 通过 onCustomEvent(name="on_interrupt") 监听
                ObjectNode customData = objectMapper.createObjectNode();
                customData.put("type", "CUSTOM");
                customData.put("name", "on_interrupt");
                ArrayNode valueArr = customData.putArray("value");
                for (ObjectNode interrupt : pendingInterrupts) {
                    valueArr.add(interrupt);
                }
                events.add(new AgUiEvent("CUSTOM", customData.toString()));
                pendingInterrupts.clear();
            }

            // RUN_FINISHED — 始终不带 interrupt outcome，避免 AbstractAgent.pendingInterrupts 冲突
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "RUN_FINISHED");
            data.put("threadId", threadId);
            data.put("runId", runId);
            events.add(new AgUiEvent("RUN_FINISHED", data.toString()));

            return events;
        }

        private static String safe(String s) {
            return s != null ? s : "";
        }
    }
}
