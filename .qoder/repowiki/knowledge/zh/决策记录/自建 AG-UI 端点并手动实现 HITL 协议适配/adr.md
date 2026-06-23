# 自建 AG-UI 端点并手动实现 HITL 协议适配

_来源：83b164d → f2b125f 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
需要实现标准的 AG-UI 协议端点 (`/awp/v1/runs`) 以支持流式输出和前端交互。AgentScope 的 PermissionEngine 对未授权工具会触发 `RequireUserConfirmEvent` 并中断执行，但官方的 `agentscope-agui-spring-boot-starter` 尚未内置 Human-in-the-Loop (HITL) 的支持逻辑。

## 决策驱动
- 填补官方 Starter 功能缺失
- 遵循官方文档最佳实践
- 支持敏感工具的人工确认

## 备选方案
- **等待官方 Starter 更新支持 HITL** _（已否决）_ — 优点：减少自研代码；缺点：阻塞项目进度，官方 Issue #1586 尚未解决
- **自建 Controller 并手动映射 HITL 事件** — 优点：立即可用，完全控制协议细节，与官方文档推荐的 `ConfirmResult` 回传机制一致；缺点：需自行维护 `AgentEventMapper` 和 `extractConfirmResults` 逻辑

## 决策
在 `AgUiController` 中自行实现 AG-UI 端点。将 `RequireUserConfirmEvent` 映射为 `REQUIRE_USER_CONFIRM` SSE 事件；在请求体中支持 `confirm_results` 字段，并将其转换为 `List<ConfirmResult>` 通过 `UserMessage` 元数据回传给 Agent，以恢复被中断的工具调用。

## 影响
实现了完整的 HITL 闭环，允许用户对 MCP 工具调用进行确认或拒绝。当前实现暂未传递 `suggestedRules`，未来需优化以支持自动批准规则的学习，减少重复确认。