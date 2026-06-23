# MVP 阶段仅支持 Session 级工作区隔离，暂不支持 Project 级共享

_来源：83b164d → f2b125f 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
业务场景中存在类似 'Project' 的需求，即多个会话共享同一套记忆和技能库。但 AgentScope 原生仅支持 `(userId, sessionId)` 二元组路由，且 `IsolationScope` 是 Agent 构建时的静态配置，无法在运行时动态切换。

## 决策驱动
- MVP 交付速度
- 实现复杂度
- 避免并发串行化副作用

## 备选方案
- **方案 A：UserId 编码模拟 Project** _（已否决）_ — 优点：无需新增 Agent 实例；缺点：无 Project 时快照膨胀；有 Project 时同 Project 并发请求被 ExecutionGuard 串行化，性能受损；日志查询困难
- **方案 B：双 Agent 实例路由** _（已否决）_ — 优点：架构清晰，符合框架设计意图；缺点：需维护两套配置和 Workspace 种子，资源占用加倍
- **方案 C：仅支持 Session 级隔离 (IsolationScope.SESSION)** — 优点：实现最简单，无并发串行问题，无快照膨胀风险，MVP 验证核心流程快；缺点：暂时无法实现跨会话的知识/记忆共享

## 决策
当前 MVP 阶段选择 `IsolationScope.SESSION`，每个会话拥有独立的工作区和沙箱。暂不实现 Project 级共享功能。接口层预留 `X-Project-Id` Header 解析位，以便未来通过方案 A 或 B 进行升级。

## 影响
简化了初始架构，避免了复杂的并发控制和状态迁移问题。但用户无法在不同会话间自动共享上下文，后续若需支持 Project，需重构 `RuntimeContext` 生成逻辑或引入多 Agent 路由机制。