# 采用 RedisDistributedStore 实现全量分布式状态管理

_来源：32efe08 → 51795e2 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
为了支持 K8s 多副本部署和无状态 Agent 实例，必须将 Agent 的状态（State）、工作区文件（BaseStore）、沙箱快照（Snapshot）和执行锁（ExecutionGuard）从本地文件系统迁移到分布式存储。

## 决策驱动
- 水平扩展能力（支持多副本部署）
- 状态持久化（Pod 重启后恢复会话）
- 配置简洁性

## 备选方案
- **本地 JsonFileAgentStateStore + 本地文件系统** _（已否决）_ — 优点：开发调试方便，无外部依赖；缺点：无法在多副本间共享状态；K8s 部署时会抛出 IllegalStateException；Pod 重启丢失上下文
- **RedisDistributedStore 一键配置** — 优点：通过 `RedisDistributedStore.fromJedis(jedis)` 自动注入 stateStore, baseStore, snapshotSpec 和 executionGuard；天然支持多租户隔离；符合 Agentscope 生产级最佳实践；缺点：大体积的沙箱快照存储在 Redis 中可能导致内存膨胀（计划中已指出后续可升级为 OSS 混合存储）

## 决策
在 `AgentConfig` 中创建 `RedisDistributedStore` Bean，并将其注入 `HarnessAgent`。利用 Redis 统一承载 Agent 的所有分布式组件，实现真正的无状态服务架构。

## 影响
实现了 Agent 服务的无状态化和高可用部署；会话状态在 Pod 重启后自动恢复；但需注意监控 Redis 内存使用情况，特别是当沙箱工作区文件较大时，需按计划在后续阶段引入 OSS 存储大对象。