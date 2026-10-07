# Sport Record Verify 变更规格：measure-like-write-path-capacity

## ADDED Requirements

### Requirement: 点赞写路径容量与对账收敛须以可复现测量登记

点赞写路径（pending flush 批量落库、对账全量重建）的容量边界与收敛语义 SHALL 以隔离环境的可复现测量登记，登记内容 SHALL 区分「调度周期与批上限的算术上界」与「实测纯处理能力」两个口径，不得混写或以算术值冒充实测吞吐。

#### Scenario: flush 服务率按队列形态实测

- WHEN 在隔离 scratch 真库与受控真实 Redis 上按预注册形态（全新对、like→unlike 抵消对、同 key 重复）seed pending 队列并直调 `flushPendingLikes()` 至排空
- THEN 每轮 LRANGE 条数、去重后净操作数、事务内批写行数、LTRIM 条数与各段耗时 SHALL 被记录且计数闭合（LTRIM 总量 = 处理总量、`record_like` 行数增量 = 净插入 − 净删除），纯处理能力与 40 ops/s 算术上界 SHALL 分口径登记。

#### Scenario: 对账成本随数据量成曲线登记

- WHEN `record_like` 以固定每 record 赞数、递增 record 数的三档规模 seed 后执行 `reconcileLikeCounts()`
- THEN 全表载入耗时、内存分组规模、逐 record Redis 写耗时与对账总耗时 SHALL 逐档登记为曲线，并附外推边界声明（本机 scratch 证据，不为生产规模背书）。

#### Scenario: 收敛语义以用例验证

- WHEN pending 丢失导致 Redis 计数/成员集与 DB 漂移后执行对账，或对账窗口覆盖未落库 pending 后执行 flush + 再次对账
- THEN Redis 计数与成员集 SHALL 分别在单轮/两跳内收敛到以 DB 行为权威源的状态并留证；对账 DEL+SADD 非原子窗口与并发写的交互 SHALL 按代码级审计登记，不注入并发时序。

#### Scenario: 测量不改变生产行为

- WHILE 本变更全部测量与审计执行期间
- THEN `src/main` 生产代码、flush-batch、调度周期、锁与幂等语义 SHALL 零改动；一切耗时与吞吐数字 SHALL 限定本机隔离环境，不得写为线上延迟改善或容量背书。
