# 对账 Redis 往返 pipeline 化实施（TASK-180）

## Why

TASK-178 E2 实测对账成本 96.3% 在逐 record 三次 Redis 往返（百万行档单轮 35.6s）；TASK-179 判别轮以 test-only 切片在真实 Redis 上完成基线 vs pipeline 对照，六判据（最终收敛态等价 / 并发写行为不劣化 / 无新增失败模式 / 锁语义不变 / 命令序可重放 / 服务率量化）全过，裁决 **GO**——本提案把判别结论落地到生产路径：`reconcileLikeCounts` 的逐 record SET/DEL/SADD 改为 Redisson spring-data `executePipelined` 分批提交，命令序与基线逐条等价（判别证据：两档 `commandOrder.identical=true`），仅提交方式不同。往返次数从 3×records 降为常数级（按批提交），对账耗时随 record 数的线性斜率大幅下降（判别 (f) 档服务率量化为验收参照）。

## What Changes

- `RecordLikeService.reconcileLikeCounts`（[L346-360](../../../record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java)）：逐 record 循环体改为「命令构造 + 分批 pipeline 提交」——每 record 仍构造 SET 计数 / DEL 成员集 / SADD 重建三命令（空成员只 DEL 不 SADD，保留防御分支），按 `PIPELINE_BATCH_SIZE`（默认 500）累积后经 `stringRedisTemplate.executePipelined` 提交；锁、分组逻辑、日志语义不变。
- 不改：调度参数（`RECONCILE_*` 10min/60s）、锁键、pending 队列语义、flush 路径、key 结构、状态机。
- IT 复用判别轮装配：新增 `RecordLikeReconcilePipelineIT`（生产方法直测，非 test 切片），沿 `TASK179_IT_*` 环境变量惯例新增 `TASK180_IT_*`；J3 六判据中 (a)(d)(e) 在生产方法上复验，(b)(c) 沿判别轮证据边界登记（连接占用未覆盖、CLIENT KILL 不支持——继承 Notice，验收不依赖）。
- 验收：同负载（判别轮 (f) 档规模）前后对账耗时绝对数字登记，不写百分比、不外推生产收益。

## Impact

- 受影响代码：`record-service` 生产一处方法体 + 新 IT；无 schema/配置/迁移变更。
- 语义风险已由 TASK-179 判别覆盖：命令序等价、最终收敛态等价、锁内执行不变；本实施的风险面收敛为「生产装配接线」——由 IT 直测生产方法兜底。
- 证据边界：读数限本机隔离环境；继承三条未覆盖项（连接占用、(b) 桶零判别力、CLIENT KILL 中断）直入验收清单；不达外部门槛前不声称任何线上收益。
