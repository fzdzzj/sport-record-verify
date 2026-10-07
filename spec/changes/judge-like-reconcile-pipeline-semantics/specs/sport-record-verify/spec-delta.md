# Sport Record Verify 变更规格：judge-like-reconcile-pipeline-semantics

## ADDED Requirements

### Requirement: 对账 pipeline 化候选须先过语义判别

将 `reconcileLikeCounts` 逐 record Redis 往迁改为 pipeline 批量提交的任何优化 SHALL 先经语义判别：以 test-only 受限切片在真实 Redis 上对照「逐 record 基线」与「pipeline 候选」，按预注册判据裁决 GO / NO-GO，未裁决前不得改生产代码。

#### Scenario: 基线语义快照

- WHEN 逐 record 基线对账在真 Redis + scratch DB 上执行并逐事件观测
- THEN 计数/成员集中间可见态序列、DEL→SADD 空窗、对账期间并发 like 的存活与被覆盖行为、锁防重语义 SHALL 被记录为基线快照。

#### Scenario: pipeline 候选对照

- WHEN 同数据同装配下以 pipeline 重放等价命令序列并逐事件观测
- THEN 与基线的全部差异（可见时点、空窗形态、失败原子性、连接占用）SHALL 逐条登记，不得只报最终态。

#### Scenario: 六判据裁决

- WHERE 判据为 (a) 最终收敛态等价、(b) 并发写丢失/覆盖行为不劣于基线、(c) 无新增失败模式、(d) 锁语义不变、(e) 命令序可重放、(f) 服务率量化登记
- THEN 任一 (a)–(e) 劣化 SHALL 裁决 NO-GO；全过 SHALL 裁决 GO，且 GO 仅授权另立实施提案，不构成对生产代码的修改许可。

#### Scenario: 判别不改变生产行为

- WHILE 本变更全部判别执行期间
- THEN `src/main` 生产代码 SHALL 零改动；一切读数 SHALL 限定本机隔离环境，服务率对比 SHALL 不写为改善百分比或生产收益承诺。
