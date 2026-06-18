# 自定义实现 AG-UI 协议端点与 HITL 机制

_来源：6bb1d7c → 83b164d 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
前端需要标准的 AG-UI 协议交互（SSE 流式输出），并支持 Human-in-the-Loop (HITL) 机制以处理敏感工具调用的用户确认。官方 `agentscope-agui-spring-boot-starter` 尚未内置 HITL 支持（Issue #1586），无法满足生产级安全确认需求。

## 决策驱动
- 协议标准化（AG-UI）
- 安全合规（HITL）
- 官方 Starter 功能缺失

## 备选方案
- **使用官方 agentscope-agui-spring-boot-starter** _（已否决）_ — 优点：开箱即用；缺点：不支持 HITL，无法处理 RequireUserConfirmEvent，导致敏感工具调用无法中断等待用户确认
- **自研 AG-UI Controller 与事件映射** — 优点：完全控制事件流，可完整实现 HITL（暴露确认事件、回传确认结果、恢复 Agent 状态）；缺点：需自行维护 AgentEvent 到 AG-UI Event 的映射逻辑，以及 Reactor Context 中的租户信息传递

## 决策
自行实现 `AgUiController` 暴露 `/awp/v1/runs` 端点。通过 `AgentEventMapper` 将 AgentScope 内部事件（如 `RequireUserConfirmEvent`）映射为 AG-UI 标准事件（`REQUIRE_USER_CONFIRM`）。在请求中支持 `confirm_results` 字段，通过 `UserMessage` 元数据将用户确认结果回传给 Agent，从而恢复被暂停的工具调用流程。

## 影响
实现了完整的多租户 SSE 流式输出和 HITL 安全确认机制。需维护自定义的事件转换逻辑和 Reactor Context 透传链路（TenantContextHolder）。当前实现暂未传递 `suggestedRules`，未来需优化以减少重复确认。