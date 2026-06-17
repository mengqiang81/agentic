package com.example.agentic.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Agent 会话服务 — SSE 流式输出核心。
 * <p>
 * 封装 HarnessAgent.streamEvents()，每次调用传入 RuntimeContext 保证多租户隔离。
 * 使用 per-run 有状态 {@link AgentEventMapper.RunMapper} 处理事件映射：
 * - 自动补发 TEXT_MESSAGE_START
 * - 缓冲 RequireUserConfirmEvent，合入 RUN_FINISHED.outcome.interrupts
 * - 标准 AG-UI camelCase 字段
 * <p>
 * SSE 格式：仅发 data: 行（不设 event: 字段），type 包含在 JSON data 内，
 * 这是 AG-UI @ag-ui/client HttpAgent 的标准解析方式。
 */
@Service
public class AgentSessionService {

    private final HarnessAgent harnessAgent;
    private final AgentEventMapper agentEventMapper;
    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    public AgentSessionService(HarnessAgent harnessAgent, AgentEventMapper agentEventMapper) {
        this.harnessAgent = harnessAgent;
        this.agentEventMapper = agentEventMapper;
    }

    /**
     * 流式输出 Agent 事件，支持 HITL 确认和标准 AG-UI resume。
     *
     * @param tenantId       租户 ID
     * @param userId         用户 ID
     * @param threadId       会话/线程 ID（AG-UI threadId）
     * @param runId          运行 ID（AG-UI runId）
     * @param userMessage    用户消息文本
     * @param confirmResults HITL 确认结果列表（可为 null 表示普通对话）
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamAgentEvents(
            String tenantId, String userId, String threadId, String runId,
            String userMessage, List<ConfirmResult> confirmResults) {

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(tenantId + ":" + userId)
                .sessionId("universal-agent" + ":" + threadId)
                .build();

        // 构造 UserMessage
        UserMessage msg;
        if (confirmResults != null && !confirmResults.isEmpty()) {
            // resume 场景：text 内容使用 "confirmed"，避免 agent 误将原始问题当作新一轮输入
            msg = UserMessage.builder()
                    .textContent("confirmed")
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                    .build();
        } else {
            msg = new UserMessage(userMessage);
        }

        // 创建 per-run 有状态映射器
        AgentEventMapper.RunMapper runMapper = agentEventMapper.createRunMapper(threadId, runId);

        return harnessAgent
                .streamEvents(msg, ctx)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(event -> log.debug("[AG-EVENT] {} | toolCallId={}",
                        event.getClass().getSimpleName(),
                        event instanceof io.agentscope.core.event.ToolResultEndEvent tre ? tre.getToolCallId() : "-"))
                .flatMapIterable(runMapper::map)
                .map(agUiEvent -> ServerSentEvent.<String>builder()
                        .data(agUiEvent.getData())  // 仅 data: 行，不设 event: 字段
                        .build());
    }

    /**
     * 流式输出 Agent 事件（普通对话，无 HITL 确认）。
     */
    public Flux<ServerSentEvent<String>> streamAgentEvents(
            String tenantId, String userId, String threadId, String runId, String userMessage) {
        return streamAgentEvents(tenantId, userId, threadId, runId, userMessage, null);
    }
}
