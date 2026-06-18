# 采用 Spring AI Alibaba 基础设施与 AgentScope HarnessAgent 2.0 分离架构

_来源：6bb1d7c → 83b164d 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
项目需要构建通用智能体平台，要求兼容传统 Spring AI 写法（ChatModel、MCP Server），同时使用最新的 AgentScope 2.0 核心能力。官方提供的 `spring-ai-alibaba-starter-agentscope` 仅适配 AgentScope 1.x 的 ReActAgent，与 2.0 版本不兼容，导致无法直接通过 Starter 集成。

## 决策驱动
- 框架版本兼容性
- 功能完整性（HarnessAgent 2.0）
- 架构解耦

## 备选方案
- **使用 spring-ai-alibaba-starter-agentscope** _（已否决）_ — 优点：集成简单，官方维护；缺点：仅支持 AgentScope 1.x，与所需的 HarnessAgent 2.0-RC3 存在依赖冲突（agentscope-core 版本不一致）
- **分离架构：SAA 提供基础设施 + 独立引入 agentscope-harness** — 优点：可使用最新的 HarnessAgent 2.0，保留 SAA 的 ChatModel/MCP/Observation 能力，两者在 Spring Boot 容器层共享基础设施但逻辑独立；缺点：需手动管理依赖版本和 Bean 配置，集成复杂度略高

## 决策
放弃使用 `spring-ai-alibaba-starter-agentscope`。采用分离架构：以 `spring-ai-alibaba 1.1.2.2` 为基础框架提供 Spring AI 标准能力（ChatModel, MCP, Observation），直接引入 `io.agentscope:agentscope-harness:2.0.0-RC3` 作为智能体核心。两者仅在 Spring Boot 容器层面共享 Redis、OTEL 等基础设施，互不干扰。

## 影响
避免了依赖冲突，能够使用 AgentScope 2.0 的最新特性（如无状态 Agent 实例、DistributedStore）。但需要自行配置 HarnessAgent Bean、分布式存储及中间件链，增加了初始配置工作量。