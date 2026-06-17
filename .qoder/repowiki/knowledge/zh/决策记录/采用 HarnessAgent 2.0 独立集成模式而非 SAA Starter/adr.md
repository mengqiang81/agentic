# 采用 HarnessAgent 2.0 独立集成模式而非 SAA Starter

_来源：32efe08 → 51795e2 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
项目需要基于 agentscope-java 2.0.0-RC3 的 HarnessAgent 构建智能体核心，但 Spring AI Alibaba (SAA) 官方提供的 starter (`spring-ai-alibaba-starter-agentscope`) 仅适配 agentscope 1.x 的 ReActAgent，存在版本不兼容问题。同时需保留 SAA 基础框架以兼容传统 Spring AI 写法（如 ChatModel、MCP Server）。

## 决策驱动
- 版本兼容性（agentscope 2.0 vs 1.x）
- 架构灵活性（解耦 Agent 核心与基础设施）
- 技术栈复用（保留 SAA 的 ChatModel/MCP 能力）

## 备选方案
- **使用 spring-ai-alibaba-starter-agentscope** _（已否决）_ — 优点：官方封装，配置简单；缺点：仅支持 agentscope 1.x，与目标版本 2.0.0-RC3 冲突；无法使用 HarnessAgent 新特性
- **直接引入 agentscope-harness 2.0 并手动集成** — 优点：完全兼容 agentscope 2.0；可独立升级 Agent 核心；与 SAA 基础设施（Redis/OTEL）在 Spring 容器层共存互不干扰；缺点：需手动配置 Bean（如 DistributedStore, TracingMiddleware）；集成复杂度略高

## 决策
弃用 `spring-ai-alibaba-starter-agentscope`，直接在 `pom.xml` 中引入 `io.agentscope:agentscope-harness:2.0.0-RC3` 和 `agentscope-extensions-redis`。SAA 仅作为基础框架提供 `spring-ai-alibaba-starter-dashscope` 等基础设施依赖，两者通过 Spring Boot 容器共享 Redis 和 OTEL 配置，但在 Agent 运行时层面保持独立。

## 影响
避免了 `agentscope-core` 的版本冲突；获得了 HarnessAgent 2.0 的无状态并发能力和分布式存储支持；但需要自行维护 `AgentConfig` 中的 Bean 装配逻辑（如 RedisDistributedStore 和 DockerFilesystemSpec 的配置）。