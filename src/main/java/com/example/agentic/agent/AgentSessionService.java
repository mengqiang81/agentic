package com.example.agentic.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

/**
 * Agent 会话服务 — SSE 流式输出核心。
 * <p>
 * 封装 HarnessAgent.streamEvents()，每次调用传入 RuntimeContext 保证多租户隔离。
 * <p>
 * 关键注意（来自官方常见坑位）：
 * - 每次 call()/streamEvents() 必须传入 RuntimeContext，否则串台
 * - 不要用 java.nio.Files 写工作区，在沙箱/Remote 模式下走 agent.getWorkspaceManager()
 * - IsolationScope 上线前定好不要改，改了等于换命名空间
 */
@Service
public class AgentSessionService {

    private final HarnessAgent harnessAgent;
    private final AgentEventMapper agentEventMapper;

    public AgentSessionService(HarnessAgent harnessAgent, AgentEventMapper agentEventMapper) {
        this.harnessAgent = harnessAgent;
        this.agentEventMapper = agentEventMapper;
    }

    /**
     * 流式输出 Agent 事件，转换为 AG-UI SSE 格式。
     *
     * @param tenantId    租户 ID（来自 X-Tenant-Id Header）
     * @param userId      用户 ID（来自 X-User-Id Header）
     * @param sessionId   会话 ID（来自 AG-UI thread_id）
     * @param userMessage 用户消息文本
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamAgentEvents(
            String tenantId, String userId, String sessionId, String userMessage) {

        // 多租户隔离：tenantId:userId 拼合作为用户标识
        // sessionId 复合：agentName:sessionId（多 agent 场景隔离）
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(tenantId + ":" + userId)
                .sessionId("universal-agent" + ":" + sessionId)
                .build();

        return harnessAgent
                .streamEvents(new UserMessage(userMessage), ctx)
                // agentscope 内部存在 Mono.block() 调用（如 ReActAgent.applySystemPromptMiddlewares），
                // 在 reactor-http-nio 线程会被拒绝，必须切到 boundedElastic 调度器
                .subscribeOn(Schedulers.boundedElastic())
                // 使用 mapNotNull：Flux.map() 不允许返回 null，未映射的内部事件会被过滤掉
                .mapNotNull(event -> agentEventMapper.toAgUiEvent(event))
                .filter(Objects::nonNull)
                .map(agUiEvent -> ServerSentEvent.<String>builder()
                        .event(agUiEvent.getType())
                        .data(agUiEvent.getData())
                        .build());
    }
}
