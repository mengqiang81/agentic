# 采用 Spring AI Alibaba 基础设施与 AgentScope HarnessAgent 2.0 分离架构

_来源：83b164d → f2b125f 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
需要构建通用智能体平台，既要利用 Spring AI Alibaba (SAA) 1.1.2.2 提供的稳定基础设施（ChatModel、MCP Server、Observation），又要使用 AgentScope 2.0.0-RC3 的 HarnessAgent 作为核心智能体。然而，SAA 官方提供的 `spring-ai-alibaba-starter-agentscope` 仅适配 AgentScope 1.x 的 ReActAgent，与 2.0 版本存在严重的依赖冲突和不兼容问题。

## 决策驱动
- 框架版本兼容性
- 避免依赖冲突
- 保留 Spring AI 生态能力

## 备选方案
- **使用 spring-ai-alibaba-starter-agentscope** _（已否决）_ — 优点：官方集成，配置简化；缺点：仅支持 AgentScope 1.x，引入 agentscope-core:1.0.x 导致与 HarnessAgent 2.0.0-RC3 版本冲突，功能受限
- **分离集成：SAA 基础包 + 独立引入 agentscope-harness** — 优点：彻底解决版本冲突，可自由使用 AgentScope 2.0 新特性（如无状态 Agent、DistributedStore），同时保留 SAA 的 DashScope ChatModel 等传统能力；缺点：需要手动配置 Bean 和依赖管理，集成复杂度略高

## 决策
在 pom.xml 中显式引入 `spring-ai-alibaba-starter-dashscope` 提供基础 AI 能力，并独立引入 `io.agentscope:agentscope-harness:2.0.0-RC3` 及其 Redis 扩展。严禁引入 `spring-ai-alibaba-starter-agentscope`。两者在 Spring Boot 容器中共存但逻辑解耦，HarnessAgent 通过 OpenAI 兼容协议调用模型，不依赖 SAA 的 Agent 编排层。

## 影响
成功规避了 AgentScope 1.x 与 2.x 的类路径冲突。系统获得了 AgentScope 2.0 的高级特性（如基于 Redis 的分布式状态存储、Docker 沙箱隔离），但失去了 SAA Starter 提供的自动化配置便利，需自行维护 `AgentConfig` 等配置类。