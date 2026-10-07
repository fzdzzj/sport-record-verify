# measure-like-write-path-capacity

## Why

点赞写路径（热写 Redis + 定时批量落库 + 低频对账）从未被测量：`flushPendingLikes` 每 5s 一轮、每轮上限 `app.like.flush-batch=200`，长期处理上界的算术值是 40 操作/s，但真实服务率取决于 LRANGE、同批去重、事务内 DB 批写与 LTRIM 的实际耗时，从未实测；`reconcileLikeCounts` 每 10 分钟全表载入 `record_like` (record_id, user_id) 对并逐 record 覆盖 Redis 计数、DEL+SADD 重建成员集，其耗时/堆峰值随数据量的增长曲线未知。TASK-137 只消除了对账读成员的 N+1；TASK-174 治理的是读路径防击穿；写路径容量与对账成本在机会总览中登记为剩余 P2：测到达率、LLEN 斜率、批次有效净操作数、flush 事务耗时、对账行数/耗时/堆峰值，先判断主导因素，再决定是否仅改一个因素。

另有两类收敛语义只有代码注释声明、无可测证据：pushPending 写失败时热路径已改 Redis 计数/成员集但 pending 丢失，依赖对账以 DB 为权威源兜底；对账覆盖窗口内未落库的 pending 会被 DB 权威值临时回退，依赖「下一轮 flush + 再下一轮对账」两跳收敛。本任务把这两类声明变成可验证的收敛用例。

## What Changes

- 纯测量 + 语义审计，零生产改动：`RecordLikeService.java`、flush-batch、两个调度周期、锁与幂等语义全部冻结。
- E1 flush 服务率测量：隔离 scratch 真库（`record_like` 沿建表脚本）+ 受控真实 Redis，三种队列形态（全新对 / like→unlike 抵消对 / 同 key 重复）下直调包私有 `flushPendingLikes()` 至排空，逐轮记录 LRANGE/去重/事务批写/LTRIM 耗时与净操作数，登记纯处理能力；与「batch/周期算术上界 40 ops/s」分口径登记，不得混写。
- E2 对账成本曲线：seed `record_like` 三档规模（约 2 万 / 20 万 / 100 万行，固定平均每 record 50 赞，单因素为 record 数），实测 `selectRecordLikePairs` 全表载入耗时、内存分组规模、逐 record Redis 写总耗时与对账总耗时，登记曲线与外推边界。
- A1 收敛语义审计：①pending 丢失漂移→对账单轮收敛；②对账窗口覆盖未落库 pending→flush→再对账两跳收敛（均 test-only 可测）；③对账 DEL+SADD 非原子窗口与并发写的交互按代码级审计登记（不注入并发时序）。
- 产出限测量报告（`docs/perf/`）+ 机器摘要 JSON + test-only IT（`*IT` 命名，`TASK178_IT_*` 环境变量，缺变量 assume 跳过不视为通过）+ 台账闭环。

## Impact

- 受影响代码：仅新增 `record-service/src/test/java/` 下 test-only IT 与 `docs/perf/` 报告/JSON；`src/main` 零改动。
- 不受影响：点赞热路径、flush/对账调度与语义、幂等双保险、读路径防击穿、现有 450 离线测试基线（IT 不被 Surefire 默认收集）。
- 证据边界：只登记本机 scratch/受控 Redis 的容量边界与耗时曲线，不声称线上延迟或吞吐改善；测量结论若指向容量或语义缺陷，修复另立提案，本任务不顺手改。
