# 基于 Session 维度的 Docker 沙箱隔离策略

_来源：32efe08 → 51795e2 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
平台需支持多租户环境下的 Skill 脚本安全执行，并要求不同会话间的上下文和工作区文件完全隔离。Agentscope 提供了多种隔离粒度（SESSION/USER/AGENT/GLOBAL），需选择适合 MVP 阶段且能避免并发副作用的方案。

## 决策驱动
- 数据隔离安全性（防止租户/会话间串台）
- 并发性能（避免执行锁串行化）
- 存储成本（控制沙箱快照大小）
- 实现复杂度

## 备选方案
- **IsolationScope.USER（用户级隔离）** _（已否决）_ — 优点：同用户下不同会话共享工作区（类似 Project 概念），减少快照数量；缺点：同一用户并发请求会被 ExecutionGuard 串行化，影响性能；无原生 Project 支持时难以区分不同业务场景的工作区
- **IsolationScope.SESSION（会话级隔离）** — 优点：每个会话拥有独立沙箱，无并发串行问题；隔离最彻底，符合多租户安全要求；实现最简单；缺点：每个会话都会产生独立的沙箱快照，长期运行可能增加存储压力（后续可通过混合 OSS 存储优化）

## 决策
在 `AgentConfig` 中配置 `DockerFilesystemSpec` 使用 `IsolationScope.SESSION`。通过 `RuntimeContext` 中的 `userId` (tenantId:userId) 和 `sessionId` (agentName:sessionId) 组合确保全局唯一性。暂不支持 Project 级工作区共享，预留 `X-Project-Id` Header 但不作为隔离维度。

## 影响
确保了多租户和多会话间的严格隔离，避免了并发请求的竞争条件；但每个会话都会生成独立的 Docker 容器和工作区快照，需在运维侧关注存储清理或未来迁移至 OSS 混合存储方案。