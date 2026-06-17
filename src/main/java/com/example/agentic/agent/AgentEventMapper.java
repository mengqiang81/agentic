package com.example.agentic.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import org.springframework.stereotype.Component;

/**
 * AgentEvent → AG-UI Event 转换器。
 * <p>
 * AG-UI 事件映射（依据 agentscope-core 2.0.0-RC3 实际事件类型）：
 * | AgentEvent          | AG-UI EventType          |
 * |---------------------|--------------------------|
 * | AgentStartEvent     | RUN_STARTED              |
 * | TextBlockDeltaEvent | TEXT_MESSAGE_CONTENT     |
 * | TextBlockEndEvent   | TEXT_MESSAGE_END         |
 * | ToolCallStartEvent  | TOOL_CALL_START          |
 * | ToolCallEndEvent    | TOOL_CALL_END            |
 * | ToolResultEndEvent  | TOOL_CALL_RESULT         |
 * | AgentEndEvent       | RUN_FINISHED             |
 * 其余事件（ModelCallStart/End、ToolResultStart、ThinkingBlock 等）作为内部事件忽略。
 */
@Component
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    public AgentEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 AgentEvent 转换为 AG-UI 事件。
     *
     * @param event AgentScope 事件
     * @return AG-UI 事件对象，如果事件类型不需要映射则返回 null
     */
    public AgUiEvent toAgUiEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "RUN_STARTED");
            data.put("session_id", safe(e.getSessionId()));
            data.put("reply_id", safe(e.getReplyId()));
            return new AgUiEvent("RUN_STARTED", data.toString());
        }
        if (event instanceof TextBlockDeltaEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TEXT_MESSAGE_CONTENT");
            data.put("delta", safe(e.getDelta()));
            data.put("reply_id", safe(e.getReplyId()));
            data.put("block_id", safe(e.getBlockId()));
            return new AgUiEvent("TEXT_MESSAGE_CONTENT", data.toString());
        }
        if (event instanceof TextBlockEndEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TEXT_MESSAGE_END");
            data.put("reply_id", safe(getReplyId(e)));
            return new AgUiEvent("TEXT_MESSAGE_END", data.toString());
        }
        if (event instanceof ToolCallStartEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TOOL_CALL_START");
            data.put("tool_call_id", safe(e.getToolCallId()));
            data.put("tool_name", safe(e.getToolCallName()));
            return new AgUiEvent("TOOL_CALL_START", data.toString());
        }
        if (event instanceof ToolCallEndEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TOOL_CALL_END");
            data.put("tool_call_id", safe(e.getToolCallId()));
            data.put("tool_name", safe(e.getToolCallName()));
            return new AgUiEvent("TOOL_CALL_END", data.toString());
        }
        if (event instanceof ToolResultEndEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "TOOL_CALL_RESULT");
            data.put("tool_call_id", safe(e.getToolCallId()));
            data.put("tool_name", safe(e.getToolCallName()));
            data.put("state", e.getState() != null ? e.getState().toString() : "");
            return new AgUiEvent("TOOL_CALL_RESULT", data.toString());
        }
        if (event instanceof AgentEndEvent e) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("type", "RUN_FINISHED");
            data.put("reply_id", safe(e.getReplyId()));
            return new AgUiEvent("RUN_FINISHED", data.toString());
        }
        // 内部/未映射事件，跳过
        return null;
    }

    /**
     * 将 AG-UI 事件序列化为 JSON 字符串
     */
    public String toJson(AgUiEvent event) {
        return event.getData();
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static String getReplyId(TextBlockEndEvent e) {
        try {
            var m = e.getClass().getMethod("getReplyId");
            Object r = m.invoke(e);
            return r != null ? r.toString() : "";
        } catch (Exception ex) {
            return "";
        }
    }
}
