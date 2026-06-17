package com.example.agentic.config;

import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * HarnessAgent 生产级配置。
 * <p>
 * 使用 RedisDistributedStore 一键配置所有分布式组件：
 * stateStore + baseStore + snapshotSpec + executionGuard。
 * Agent 实例完全无状态（v2.0 特性），单例即可并发服务多 Session。
 */
@Configuration
public class AgentConfig {

    @Value("${agent.workspace:workspace}")
    private String workspacePath;

    @Bean
    public JedisPooled jedisPooled(@Value("${spring.data.redis.url:redis://localhost:6379}") String redisUri) {
        return new JedisPooled(redisUri);
    }

    @Bean
    public DistributedStore distributedStore(
            JedisPooled jedis,
            @Value("${agentic.redis.key-prefix:agentic}") String keyPrefix) {
        // 第二个参数是 keyPrefix：所有 Redis key 均以 "agentic:" 开头，便于查看与清理
        return RedisDistributedStore.fromJedis(jedis, keyPrefix);
    }

    @Bean
    public HarnessAgent harnessAgent(
            @Value("${agent.model.base-url}") String baseUrl,
            @Value("${agent.model.api-key}") String apiKey,
            @Value("${agent.model.model-name}") String modelName,
            @Value("${agent.sandbox.image:python:3.12-slim}") String sandboxImage,
            DistributedStore store) {

        Path workspace = Paths.get(workspacePath);

        return HarnessAgent.builder()
                .name("universal-agent")
                .model(OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .stream(true)
                        .build())
                .workspace(workspace)
                // === 分布式一键配置（自动注入 stateStore + baseStore + snapshotSpec + executionGuard） ===
                .distributedStore(store)
                // === 沙箱模式：多租户隔离 + 脚本执行 ===
                // 注意：DockerFilesystemSpec 的链式调用顺序需先调子类方法（image），再调父类方法（isolationScope/workspaceProjectionRoots）
                .filesystem(((DockerFilesystemSpec) new DockerFilesystemSpec()
                        .image(sandboxImage))
                        .isolationScope(IsolationScope.SESSION)  // 每个 session 独立沙箱
                        .workspaceProjectionRoots(List.of(       // 控制投射到沙箱的种子文件
                                "AGENTS.md", "skills", "knowledge", "tools.json")))
                // === 上下文压缩 ===
                .compaction(CompactionConfig.builder()
                        .triggerMessages(50)   // 每 50 条消息触发压缩
                        .keepMessages(20)      // 压缩后保留最近 20 条
                        .build())
                // === 大工具结果卸载（超 80K 落盘 + 占位符） ===
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                // === Tracing Middleware ===
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
    }
}
