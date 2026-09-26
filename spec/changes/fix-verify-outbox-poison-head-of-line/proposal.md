# 提案：避免重试耗尽的 outbox 行堵住后续判定事件

## Why

当前 `VerifyEventOutboxMapper.selectPendingBatch(limit)` 按 `status='PENDING' ORDER BY id LIMIT limit` 取最早的行；`VerifyOutboxRelay.relay()` 对 `retry_count >= maxRetry` 的行只告警并 `continue`，既不发送也不更新状态。因此当前 100 条最早 PENDING 行若都已重试耗尽（默认上限 16、批大小 100），每轮仍取同一批，后续可投递行永久不可见。代码路径与 SQL 可确认此条件推导；**尚无真实运行时饥饿事件或新增红测证据**，实施时必须先证明。TASK-138 曾测得另一场景下 outbox PENDING 1012/SENT 998，但未证明当时存在耗尽行，不得拿它冒充本缺陷的事故证据。

这是可靠投递与吞吐上界的先决正确性问题，不先调整 relay 周期、批量大小、线程数或 MQ 重试参数。上一轮 TASK-141 已表明仅调池容量 10→20 不存在稳定收益，本提案不回到池调参。

## What Changes

1. 对同一条 Mapper SQL 在隔离的 scratch MySQL（或等价可执行 SQL 的测试环境）构造 `batchSize` 条最小 ID、`status=PENDING, retry_count=maxRetry` 的耗尽行及其后一条可投递行；确认旧 SQL 首轮只返回耗尽行，并用 relay 测试证明后续事件未投递。禁止只用 Mockito 预制“已经过滤”的列表冒充 SQL 行为红。
2. 最小修复：取批查询增加 `retry_count < maxRetry` 资格条件，由 relay 将其当前上限传入 Mapper；耗尽行保留原状态和原数据供人工核查，不自动删除、重置、改写 eventId 或重投。既有 `(status,id)` 索引仍可服务顺序扫描，但可能需扫描大量耗尽行；在 scratch 库记录 EXPLAIN 与扫描/耗时，**不得声称这一步改善查询耗时**。如耗尽行规模使 SQL 退化，再报告剩余问题，另立状态迁移/索引方案，不在此变更顺手扩围。
3. 新增判别：队首已满批耗尽行时，下一条可投递行仍可被 relay 选中并发送；少于阈值的失败行仍按原有顺序重试；达到阈值的行仍留库且不发送；发送失败计数、成功标 SENT、eventId 原样透传、多实例互斥不变。边界 `retry_count=maxRetry-1` 和 `=maxRetry` 必须覆盖；阈值配置未变。
4. 同一次作业完成仓库 offline verify-service 测试、必要的 scratch SQL 验证、三件套勾选、TASK-142 spec/handoff/PLAN、契约与本地提交；不 push、不建 PR。不触碰既有归档移名、`.codex/`、`.trae/` 或未跟踪索引提案，不在真实演示库制造坏行。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的判定事件可靠投递；见本变更 spec-delta。
- 预计代码：`verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`、`verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` 及对应测试/必要的隔离 SQL 验证。无新依赖、无生产表结构迁移、无对外 API 改动。
- 兼容：已有 `PENDING` 且 `retry_count>=maxRetry` 的行自动不再占用发送批次，但仍保留待人工处理；上限配置降低时更多旧行可变为耗尽，属既有阈值语义，报告中应说明。若 `maxRetry<=0` 或数据库中 `retry_count` 非法，先确认配置/DDL 契约，不擅改重试策略。
- 风险：大量历史耗尽行下顺序扫描代价可能升高；通过 scratch EXPLAIN 与规模说明风险，不凭空加索引或把 100/5s 推演值当作实测吞吐。

## 停止条件

若旧 SQL 无法在相同隔离数据上复现“前 N 耗尽行遮挡后一可投递行”，或发现现有其他调度/清理路径会在查询前自动移走这些行，应停止修改并回报反证。若真实 SQL 验证环境不可用，行为红记未覆盖，不以 mock 代替并宣称完成此门槛。业务事件重复消费/顺序问题以现有幂等契约为界，不能为提速更改。
