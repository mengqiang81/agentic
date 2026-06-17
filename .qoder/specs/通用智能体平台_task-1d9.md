# 通用智能体平台 — 实现规范

## Context

以 **spring-ai-alibaba 1.1.2.2**（稳定版）为基础框架，提供传统 Spring AI 写法兼容（ChatModel、MCP Server 等）。以 **agentscope-java 2.0.0-RC3** 的 `HarnessAgent` 为智能体核心，独立运行不接入 SAA Graph 编排。两者仅在 Spring Boot 容器层面共享基础设施（Redis、OTEL、配置等）。

**不使用 `spring-ai-alibaba-starter-agentscope`**，因其仅适配 agentscope 1.x 的 ReActAgent，与 agentscope 2.0 不兼容。

核心需求：
- 多租户 Session 隔离（Header 传入 `X-Tenant-Id` / `X-User-Id`）
- SSE 流式输出（WebFlux `text/event-stream`）
- 动态注册 Skill 和 MCP 工具
- 完整 AG-UI 协议端点（`/awp/v1/runs`）
- Skill 中的脚本安全运行（本地沙箱）
- 全链路 Tracing（OTEL + Langfuse / AgentScope Studio）

---

## 技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 基础框架 | `spring-ai-alibaba 1.1.2.2` + Spring Boot 3.5.x | 提供 Spring AI ChatModel、MCP、Observation 等传统写法 |
| Agent 核心 | `io.agentscope:agentscope-harness:2.0.0-RC3` | 直接引入，不通过 SAA starter |
| Web 框架 | Spring Boot WebFlux | SSE 流式输出 |
| Session 存储 | Redis（`agentscope-extensions-redis:2.0.0-RC3`） | 多租户状态隔离 |
| LLM | DeepSeek-V4-Flash | 通过 agentscope 的 `OpenAIChatModel`（OpenAI 兼容协议）接入 |
| Skill 脚本沙箱 | 本地沙箱（agentscope-harness 内置） | 绑定 userId+sessionId 维度 |
| Tracing | OTEL SDK + Langfuse OTLP Exporter | 或切换到 AgentScope Studio |
| 构建工具 | Maven | |
| JDK | 17+ | |

---

## 项目结构

```
agentic/
├── pom.xml
└── src/main/
    ├── java/com/example/agentic/
    │   ├── AgenticApplication.java
    │   ├── config/
    │   │   ├── AgentConfig.java          # HarnessAgent + DistributedStore + Docker沙箱
    │   │   ├── McpToolConfig.java        # MCP 工具注册
    │   │   ├── SkillConfig.java          # Skill 仓库配置
    │   │   ├── TracingConfig.java        # OTEL SDK 配置
    │   │   └── ShutdownConfig.java       # 优雅停机
    │   ├── tenant/
    │   │   └── TenantContextHolder.java  # Reactor Context WebFilter
    │   ├── controller/
    │   │   ├── AgUiController.java       # AG-UI /awp/v1/runs 端点
    │   │   ├── SkillController.java      # Skill CRUD REST API
    │   │   └── McpToolController.java    # MCP 动态注册 REST API
    │   └── agent/
    │       ├── AgentEventMapper.java     # AgentEvent → AG-UI Event 转换
    │       └── AgentSessionService.java  # 封装 HarnessAgent.streamEvents()
    └── resources/
        ├── application.yml
        └── workspace/
            ├── AGENTS.md                 # Agent 人格定义（种子）
            ├── tools.json                # MCP 工具白名单
            ├── skills/                   # 初始 Skill 目录
            └── knowledge/                # 知识库目录
```

---

## Task 1 — 项目初始化与 pom.xml

**文件：`pom.xml`**

架构说明：SAA 1.1.2.2 提供 Spring AI 基础设施（ChatModel、Observation、MCP Server 等），
agentscope-harness 2.0.0-RC3 提供 HarnessAgent 智能体核心，两者独立但共存。

```xml
<!-- BOM 统一版本 -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-bom</artifactId>
      <version>1.1.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.1.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- === SAA 基础设施（传统 Spring AI 写法） === -->
  <dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
  </dependency>

  <!-- === AgentScope HarnessAgent（智能体核心） === -->
  <dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>2.0.0-RC3</version>
  </dependency>

  <!-- AgentScope Redis 扩展（多租户 Session 持久化） -->
  <dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>2.0.0-RC3</version>
  </dependency>

  <!-- === Spring Boot WebFlux（SSE 流式输出） === -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
  </dependency>

  <!-- === Redis === -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
  </dependency>

  <!-- === OTEL Tracing === -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
  </dependency>
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
  </dependency>
</dependencies>
```

> **注意**：不引入 `spring-ai-alibaba-starter-agentscope`，避免其内部依赖的 `agentscope-core:1.0.x` 与 `agentscope-harness:2.0.0-RC3` 发生版本冲突。

---

## Task 2 — application.yml 配置

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URI:redis://localhost:6379}

agent:
  workspace: ${AGENT_WORKSPACE:/var/agentscope/workspace}  # 生产用绝对路径
  model:
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${DEEPSEEK_API_KEY}
    model-name: ${DEEPSEEK_MODEL:deepseek-v4-flash}
  sandbox:
    image: python:3.12-slim       # Docker 沙箱镜像
    isolation-scope: SESSION       # SESSION | USER | AGENT | GLOBAL

otel:
  exporter:
    otlp:
      endpoint: ${LANGFUSE_OTEL_ENDPOINT:http://localhost:4318}

server:
  shutdown: graceful              # Spring Boot 优雅停机
  port: 8080
```

---

## Task 3 — HarnessAgent Bean（生产级配置）

**文件：`config/AgentConfig.java`**

核心逻辑（参考官方 Going to Production 指南）：
- 使用 `RedisDistributedStore.fromJedis(jedis)` **一键配置**所有分布式组件（stateStore + baseStore + snapshotSpec + executionGuard）
- `HarnessAgent` 使用 agentscope 自带的 `OpenAIChatModel`（OpenAI 兼容协议）接入 DeepSeek-V4-Flash
- Filesystem 使用 `DockerFilesystemSpec` + `IsolationScope.SESSION` 实现多租户沙箱隔离
- Agent 实例完全无状态（v2.0 特性），单例即可并发服务多 Session
- 多租户隔离通过 `RuntimeContext.userId(tenantId + ":" + userId).sessionId(sessionId)` 实现
- 通过 `.middlewares()` 注入 `OtelTracingMiddleware` 实现全链路 Tracing
- 显式配置 `CompactionConfig`、`ToolResultEvictionConfig`

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.core.memory.compaction.CompactionConfig;
import io.agentscope.core.memory.compaction.ToolResultEvictionConfig;

@Configuration
public class AgentConfig {

    @Value("${agent.workspace:/var/agentscope/workspace}")
    private String workspacePath;

    @Bean
    public JedisPooled jedisPooled(@Value("${spring.data.redis.url:redis://localhost:6379}") String redisUri) {
        return new JedisPooled(redisUri);
    }

    @Bean
    public DistributedStore distributedStore(JedisPooled jedis) {
        return RedisDistributedStore.fromJedis(jedis);
    }

    @Bean
    public HarnessAgent harnessAgent(
            @Value("${agent.model.base-url}") String baseUrl,
            @Value("${agent.model.api-key}") String apiKey,
            @Value("${agent.model.model-name}") String modelName,
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
            .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.SESSION)  // 每个 session 独立沙箱
                .anonymousUserId("_anonymous")           // 未传 userId 时的 fallback
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
```

**与 SAA 的关系：**SAA 的 DashScope ChatModel Bean 仍然可用于非智能体场景（如简单的 ChatClient 调用、RAG 等传统写法），与 HarnessAgent 互不干扰。

---

## Task 4 — MCP 工具注册

**文件：`config/McpToolConfig.java`**

支持两种注册方式：
1. **静态注册**（启动时）：通过 `McpClientBuilder` 注册到 HarnessAgent Toolkit
2. **动态注册**（运行时）：通过 REST API `POST /api/tools/mcp` 热插拔

```java
// 静态 MCP 注册示例
McpClient mcpClient = McpClientBuilder.builder()
    .transport(new SseHttpTransport("http://localhost:3000/mcp"))
    .build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(mcpClient);
```

---

## Task 5 — Skill 注册与脚本沙箱

**文件：`config/SkillConfig.java` + `controller/SkillController.java`**

Skill 管理（四层合成优先级从低到高）：
| 层 | 来源 | 说明 |
|---|---|---|
| 1 | 项目全局 `.projectGlobalSkillsDir(Path)` | 个人开发机 |
| 2 | Marketplace `.skillRepository(...)` | 跨项目共享（MySQL/Git/Nacos） |
| 3 | 工作区 `workspace/skills/` | 项目专属，进 git |
| 4 | 用户隔离 `<userId>/skills/` | 用户级覆盖 |

生产配置：
```java
// 在 HarnessAgent.builder() 中添加：
.skillRepository(MysqlSkillRepository.builder(dataSource)
    .databaseName("agentscope")
    .skillsTableName("skills")
    .createIfNotExist(false)       // 生产环境预建表，禁止运行时自动 DDL
    .writeable(false)              // agent 端只读，写回走管理台
    .build())
```

生产 Checklist：
- 优先 `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository` —— 平台集中治理
- 开启 `enableSkillManageTool` 让 agent 自己起草新 skill 时，必须配 `enableSkillPromotionGate(...)`；生产严禁 `autoPromote=true`
- `NacosSkillRepository` 是 `AutoCloseable`，Spring 用 `@PreDestroy` 关闭，否则泄漏订阅

Skill REST API：
- `POST /api/skills` — 上传新 Skill（写入 MySQL/workspace）
- `GET /api/skills` — 列出当前 Skill
- `PUT /api/skills/{name}` — 更新 Skill
- `DELETE /api/skills/{name}` — 删除 Skill

脚本沙箱：
- 通过 `DockerFilesystemSpec` 实现（已在 Task 3 配置）
- Skill 中的代码工具在沙箱容器内执行，与宿主机完全隔离
- `IsolationScope.SESSION` 确保每个会话独立沙箱
- Snapshot 由 `distributedStore` 自动注入，pod 重启 / 容器销毁后自动恢复工作区

---

## Task 6 — AG-UI 协议端点

**文件：`controller/AgUiController.java`**

实现标准 AG-UI HTTP SSE 端点：
```
POST /awp/v1/runs
Content-Type: application/json
Accept: text/event-stream

Request Body（AG-UI RunAgentInput）:
{
  "thread_id": "session-xxx",
  "run_id": "run-xxx",
  "messages": [...],
  "state": {...}
}
```

AG-UI 事件映射（`agent/AgentEventMapper.java`）：
| AgentEvent | AG-UI EventType |
|---|---|
| `RunStartEvent` | `RUN_STARTED` |
| `TextDeltaEvent` | `TEXT_MESSAGE_CONTENT` |
| `TextEndEvent` | `TEXT_MESSAGE_END` |
| `ToolCallStartEvent` | `TOOL_CALL_START` |
| `ToolCallEndEvent` | `TOOL_CALL_END` |
| `ToolResultEvent` | `TOOL_CALL_RESULT` |
| `RunEndEvent` | `RUN_FINISHED` |

多租户来源：从 `X-Tenant-Id`、`X-User-Id` Header 提取，构建 `RuntimeContext`

---

## Task 7 — SSE 流式输出核心

**文件：`agent/AgentSessionService.java`**

```java
public Flux<ServerSentEvent<String>> streamAgentEvents(
        String tenantId, String userId, String sessionId, String userMessage) {

    // 多租户隔离：tenantId:userId 拼合作为用户标识
    // userId 复合：tenantId:userId；sessionId 复合：agentName:sessionId（多 agent 场景隔离）
    RuntimeContext ctx = RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId("universal-agent" + ":" + sessionId)
        .build();

    return harnessAgent
        .streamEvents(UserMessage.of(userMessage), ctx)
        .map(event -> agentEventMapper.toAgUiEvent(event))
        .filter(Objects::nonNull)
        .map(agUiEvent -> ServerSentEvent.<String>builder()
            .event(agUiEvent.getType())
            .data(jsonSerializer.toJson(agUiEvent))
            .build());
}
```

**关键注意（来自官方常见坑位）：**
- 每次 `call()` / `streamEvents()` **必须**传入 `RuntimeContext`，否则所有请求共享 `defaultSessionId` 造成串台
- 不要用 `java.nio.Files` 写工作区，在沙箱/Remote 模式下会落到错的位置，永远走 `agent.getWorkspaceManager()`
  - **例外**：builder 装配时的种子文件（initWorkspaceIfAbsent）还没有运行时上下文，用 `java.nio.Files` 是 OK 的
- `IsolationScope` 上线前定好不要改，改了等于“换命名空间”，旧数据不会自动迁移
- 本地 `JsonFileAgentStateStore` + K8s 多副本部署会在 build() 时抛 `IllegalStateException`，这是设计如此

---

## Task 8 — Tracing 配置（OTEL + Langfuse）

**文件：`config/TracingConfig.java`**

- Tracing 通过 `OtelTracingMiddleware` 注入到 HarnessAgent 的 Middleware 链（已在 Task 3 配置）
- 使用 `opentelemetry-sdk` + `opentelemetry-exporter-otlp` 将 Trace 导出到 Langfuse（兼容 OTEL HTTP 协议）
- Span 层级：`/awp/v1/runs` → `agent.run` → `model.call` → `tool.call`
- 支持通过环境变量 `LANGFUSE_OTEL_ENDPOINT` 切换到 AgentScope Studio

```java
@Configuration
public class TracingConfig {

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${otel.exporter.otlp.endpoint}") String endpoint) {
        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(Resource.builder()
                .put("service.name", "agentic-platform")
                .build())
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }
}
```

---

## Task 9 — 多租户 Tenant Context

**文件：`tenant/TenantContextHolder.java`**

- 使用 Reactor `Context` 在 WebFlux 响应式链中传递 `tenantId`/`userId`
- 通过 `WebFilter` 从 HTTP Header 提取并注入 Reactor Context
- `AgentSessionService` 从 Reactor Context 还原身份信息

---

## Task 10 — workspace 初始化与 tools.json

**文件：`resources/workspace/`**

工作区目录布局：
```
workspace/
├── AGENTS.md                # Agent 人格/指令定义（种子，进 git）
├── MEMORY.md                # 长期记忆（Remote 写入后接管）
├── tools.json               # MCP 工具白名单
├── skills/                  # 初始 Skill 目录
└── knowledge/               # 知识库目录
```

**tools.json 工具白名单**（重要）：
```json
{
  "allow": [
    "read_file",
    "write_file",
    "run_command",
    "memory_search",
    "agent_spawn",
    "read_skill",
    "mcp:*"
  ]
}
```

> **坑位警告**：`tools.json` 的 `allow` 会过滤内置工具！用白名单时务必保留 `read_file`、`memory_search`、`agent_spawn` 等，否则整套内置工具一起被砍。

---

## Task 11 — 优雅停机与生产 Checklist

**文件：`config/ShutdownConfig.java`**

```java
@Configuration
public class ShutdownConfig {
    // GracefulShutdownManager 默认注册 JVM hook
    // 接好 SIGTERM，等待 inflight 请求完成
    // 可通过 setConfig(...) 调 inflight 等待时间
}
```

生产部署 Checklist：
| 关注点 | 推荐配置 |
|---|---|
| 会话/AgentState | `RedisDistributedStore` 一键注入；(userId, sessionId) 承载租户/用户/agent 维度 |
| 工作区文件 | `distributedStore` 注入 BaseStore + RemoteFilesystemSpec + IsolationScope.USER |
| 沙箱快照 | `distributedStore` 自动注入 SandboxSnapshotSpec；**生产升级**：混合 store，状态走 Redis、大快照走 OSS（`OssDistributedStore.sandboxSnapshotSpec()`），避免 Redis 内存爆炸 |
| Skill 治理 | `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`；agent 端禁用 autoPromote |
| 优雅停机 | `GracefulShutdownManager` + SIGTERM |
| 可观测 | `OtelTracingMiddleware` + OTLP exporter |
| 限流 | 自写 `MiddlewareBase(onModelCall)` 控制 LLM 调用速率 |
| sessionId 组成 | 多 agent 场景 `sessionId(agentId + ":" + sessionId)` 避免不同 agent 的同名 session 互踩 |
| workspace 路径 | 生产用绝对路径（如 `/var/agentscope/workspace`），开发可用相对路径 |

*生产升级路径（混合 DistributedStore）：**

当沙箱工作区快照增大（>10MB）时，应将快照从 Redis 迁移到 OSS：
```java
// 混合 Store：状态/执行锁用 Redis，大快照用 OSS
DistributedStore store = DistributedStore.builder()
    .agentStateStore(redisStore.agentStateStore())
    .baseStore(redisStore.baseStore())
    .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())     // 大对象走 OSS
    .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
    .build();
```

---

## 未实现：Project 级工作区（设计备忘）

### 需求场景

类似 Claude Projects / Qoder Workspace 的能力：每个 user 可以创建多个 project，每个 project 是一个独立工作区（拥有自己的记忆、技能、知识库），同一 project 下的多个会话共享该工作区，但不同 project 之间完全隔离。

```
user: alice
├── project-A
│   ├── session-1  ← 共享 project-A 的 MEMORY.md / skills / knowledge
│   └── session-2  ← 共享 project-A 的 MEMORY.md / skills / knowledge
└── project-B
    ├── session-3  ← 共享 project-B（与 A 隔离）
    └── session-4
```

### agentscope 原生模型的局限

agentscope 只认 `(userId, sessionId)` 二元组路由状态，没有原生 "project" 概念。`IsolationScope` 是 builder 级别配置，不能 per-call 动态切换。

### 可行方案 A：userId 编码（折中）

统一使用 `IsolationScope.USER`，通过 userId 组成控制隔离粒度：

```java
String effectiveUserId;
if (projectId != null && !projectId.isBlank()) {
    // 有 project：同 project 的所有 session 共享 workspace
    effectiveUserId = tenantId + ":" + userId + ":" + projectId;
} else {
    // 无 project：用 sessionId 充当"匿名 project"，等效 SESSION 隔离
    effectiveUserId = tenantId + ":" + userId + ":" + sessionId;
}

RuntimeContext ctx = RuntimeContext.builder()
    .userId(effectiveUserId)
    .sessionId("universal-agent:" + sessionId)
    .build();
```

**副作用**：
- 无 project 时每 session 产生独立沙箱快照（快照膨胀）
- 有 project 时同 project 的并发 call 被 executionGuard 串行化
- 会话日志散落在不同 namespace 下，难以按用户聚合查询

### 可行方案 B：两个 Agent 实例（更干净）

```java
@Bean public HarnessAgent sessionAgent(...)  { ... .isolationScope(SESSION) ... }
@Bean public HarnessAgent projectAgent(...)  { ... .isolationScope(USER) ... }
```

Controller 根据 `X-Project-Id` 是否存在路由到不同实例。

**优点**：完全对齐 agentscope 设计意图，无副作用。
**缺点**：两个实例，配置重复，workspace 种子维护两份。

### 当前权衡

**不支持 project，仅支持 session 级工作区。** 理由：
- MVP 阶段优先验证核心流程（SSE 流式、工具调用、多租户隔离）
- `IsolationScope.SESSION` 最简单、无并发串行问题、无快照膨胀
- 后续升级路径清晰（方案 A 改动集中在 Service 层，方案 B 改动集中在 Config + Controller 层）

接口层预留 `X-Project-Id` header 的解析位（TenantContextHolder），但当前不作为隔离维度使用。

---

## 验证方案

1. **启动验证**
   ```bash
   source ~/.zshrc   # 加载 DEEPSEEK_API_KEY 等环境变量
   mvn spring-boot:run
   ```

2. **SSE 流式对话测试**
   ```bash
   curl -N -X POST http://localhost:8080/awp/v1/runs \
     -H "Content-Type: application/json" \
     -H "X-Tenant-Id: tenant-1" \
     -H "X-User-Id: alice" \
     -d '{"thread_id":"s1","run_id":"r1","messages":[{"role":"user","content":"hello"}]}'
   ```

3. **Skill 注册测试**
   ```bash
   curl -X POST http://localhost:8080/api/skills \
     -H "Content-Type: application/json" \
     -d '{"name":"search","content":"# Search\n搜索工具...\n```python\nimport requests\n```"}'
   ```

4. **MCP 工具注册测试**
   ```bash
   curl -X POST http://localhost:8080/api/tools/mcp \
     -d '{"transport":"sse","url":"http://localhost:3000/mcp"}'
   ```

5. **Redis Session 隔离验证**：同一 `sessionId` + 不同 `userId`/`tenantId` 发起两路并发对话，确认上下文不串

6. **Tracing 验证**：Langfuse 控制台查看 Trace 链路，确认 span 层级完整