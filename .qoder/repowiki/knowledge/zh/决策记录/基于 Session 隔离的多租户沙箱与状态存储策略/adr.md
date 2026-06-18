# 基于 Session 隔离的多租户沙箱与状态存储策略

_来源：6bb1d7c → 83b164d 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
平台需支持多租户（Tenant/User）隔离，且智能体执行脚本需在安全沙箱中运行。AgentScope 原生仅支持 `(userId, sessionId)` 二元组路由，缺乏原生的 'Project' 概念。需在 MVP 阶段确定隔离粒度，平衡安全性、并发性能与存储成本。

## 决策驱动
- 多租户数据隔离
- 沙箱安全性
- 并发性能（避免串行化）
- 存储成本（避免快照膨胀）

## 备选方案
- **Project 级隔离（方案 A：UserId 编码）** _（已否决）_ — 优点：同 Project 下 Session 共享工作区；缺点：无 Project 时快照膨胀；有 Project 时并发调用被 ExecutionGuard 串行化；日志难以按用户聚合
- **Project 级隔离（方案 B：双 Agent 实例）** _（已否决）_ — 优点：符合设计意图，无副作用；缺点：需维护两个 Agent 实例，配置重复，Workspace 种子文件需维护两份
- **Session 级隔离（IsolationScope.SESSION）** — 优点：实现最简单，无并发串行问题，无快照膨胀风险，适合 MVP 快速验证；缺点：不同 Session 间无法共享工作区文件（后续可通过混合 Store 或 Project 功能升级）

## 决策
MVP 阶段仅支持 Session 级工作区隔离。使用 `IsolationScope.SESSION` 配合 `DockerFilesystemSpec` 实现每个会话独立的 Docker 沙箱。多租户身份通过 `RuntimeContext` 中的 `userId` (tenantId:userId) 和 `sessionId` (agentName:sessionId) 复合键实现。状态存储使用 `RedisDistributedStore` 一键配置。

## 影响
实现了最简化的多租户隔离，避免了并发锁竞争和存储浪费。但暂时不支持跨 Session 的项目级上下文共享。预留了 `X-Project-Id` Header 解析位，未来可通过切换为 `IsolationScope.USER` 或引入混合 Store（Redis 存状态 + OSS 存大快照）来支持 Project 级特性。