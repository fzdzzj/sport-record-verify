# TASK-137 Handoff

## 结论

TASK-137（点赞对账去 N+1；flush 批次可配置）已完成最小实现与 offline 定向红绿验证。业务修订与测试绑定本地提交 `2f711d9444e389530ec81e9d878855fcf5a98ce2`（`fix(record): 点赞对账去 N+1，flush 批次改为可配置`，4 文件）；台账两件套与 PLAN 验收记录随收口提交入库（收口提交哈希由任务回传）。**未 push、未建 PR**。

## 编号、基线与外部状态

- 任务编号：`TASK-137`（旧交接文档写的 TASK-135 已被总榜缓存任务占用，核对后启用 TASK-137）。
- 开工/对照基线：`1d546ea08501a2577e6652bb2ebb2b814e3771ec`（当时 HEAD，工作树除既有未跟踪 `.trae/` 外干净）。
- 状态：**已本地提交**——业务修订与测试 `2f711d9444e389530ec81e9d878855fcf5a98ce2`，台账收口提交哈希由任务回传；未 push、未建 PR；`.trae/` 未触碰。
- 红绿取证日志写于仓库外临时目录（`%TEMP%/task137-red.log`、`task137-green.log`），不入库。

## 实现要点

- **F15 对账**：`RecordLikeMapper` 新增 `selectRecordLikePairs()`（`SELECT record_id, user_id FROM record_like`）；`reconcileLikeCounts` 改为一次批量查询 + `LinkedHashMap` 内存按 record_id 分组，再逐 record 覆盖计数（DB 行数）与成员集（DEL+SADD）。旧方法 `selectDistinctRecordIds` / `selectUserIdsByRecordId` 保留，javadoc 注明对账路径对其调用须保持 0 次（判别式测试约束）。
- **F16 flush**：`FLUSH_BATCH` 常量删除，改为 `@Value("${app.like.flush-batch:200}")` 字段（仓库既有 `@Value` 字段注入风格）；`range` 上界 `flushBatch - 1`；`application.properties` 显式 `app.like.flush-batch=200`。**无 Micrometer、无队列上限、无背压**。
- 语义不变项：对账「计数以 DB 行数覆盖 / DEL+SADD 重建 / 空成员只删不 SADD（防御分支保留，分组结构下天然非空）/ 锁竞争跳过本轮」；flush「5s 周期 / last-wins / 失败不 trim / 锁跳过 / 插入删除同事务」。批次配置仅是批量大小，不代表实测吞吐——本台账不写任何 ops/s 吞吐结论。
- 测试口径：record-service 模块用例 80 → 83（新增 4：对账判别式、对账锁跳过、flush 批次可配置、真实 properties 键名防漂移；替换 1：旧逐条对账测试并入对账判别式）。

## 红绿证据（本轮，Git Bash 实跑）

红阶段说明：对主代码仅做**纯增量脚手架**（Mapper 新增 `selectRecordLikePairs`、Service 新增未接线的 `flushBatch` 字段、properties 新增配置行），既有行为路径一行未动；判别式红为**行为红**（调用次数 / LRANGE 上界），非编译错。

先红：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → 退出码 `1`、`BUILD FAILURE`：

- `reconcile_singleBatchQuery_fixesRedisFromDb`：`NeverWantedButInvoked`——`selectUserIdsByRecordId` 在 `RecordLikeService.reconcileLikeCounts` 被逐 record 调用（arguments [1]），违反「改后 0 次」判别式（旧实现对每条 record 调一次，两条记录 = 2 次）；
- `flush_batchSizeConfigurable_takesOnlyConfiguredBatch`：`Argument(s) are different`——期望 `range("like:pending:ops", 0, 1)`，实际 LRANGE 上界 199（写死）；
- 模块汇总：record-service `Tests run: 83, Failures: 2, Errors: 0, Skipped: 0`；上游 common `36/0/0/0`。

后绿：同一命令（绑定最终工作树，含最后一次 javadoc 订正）→ 退出码 `0`、`BUILD SUCCESS`：

- record-service `Tests run: 83, Failures: 0, Errors: 0, Skipped: 0`（`RecordLikeServiceTest` `18/0/0/0`）；上游 common `36/0/0/0`。
- 另两项定向覆盖：`reconcile_lockNotAcquired_skip`（对账锁竞争跳过：不扫描、不写 Redis）、`flush_batchConfigKeyPresentInRealApplicationProperties`（真实 `application.properties` 键名与默认值 `app.like.flush-batch=200` 防漂移）。

## 实际改动清单

- record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/main/resources/application.properties
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-137/spec.md
- work/mailbox/tasks/TASK-137/handoff.md

## 未覆盖与已知影响（不得写成通过）

- **真实 Redis / MySQL 联调**：对账与 flush 的真中间件 IT 未跑（本轮无真实中间件环境，`--it` 未执行），记**未覆盖**；未把单测绿写成端到端通过。
- **online / CI / push / PR**：未覆盖；**未达外部门槛**。
- **`--pl record-service` 以外的模块**：本轮未重跑（`-am` 连带的上游 api 无单测、common 已随跑通过）。
- **静态三件套**：checkstyle 仅 leaderboard-service 配置了规则集，record-service 无该门槛，未跑 `--static`。
- 对账仍为全表扫描（沿 Mapper 注释原话：演示规模可直接全扫，生产可改增量游标/位图），规模随 record_like 线性增长，本轮不解决。

## 契约状态

提交前实跑（`--baseline=1d546ea08501a2577e6652bb2ebb2b814e3771ec`，改动尚在工作树）→ 总体 `rc=1`（判据 A=0、判据 B=1）：

- **TASK-137 判据 A 通过**（两件套齐全）；**TASK-137 判据 B 通过**（「实际改动清单」与工作树相对基线的改动集完全一致：4 个 record-service 文件 + 本任务两件套 + PLAN.md，共 7 文件；`.trae/` 被脚本排除）。
- 总体失败来自 **TASK-136 的历史清单重审**：其「实际改动清单」所列业务文件均已随基线 `1d546ea` 提交（不在本次工作树 diff 中），但 `work/mailbox/PLAN.md` 为共享台账、被本任务再次修改，构成交叠触发其按「在途回传」重审并报清单多报。与 TASK-136 台账自记的「共享工作树/历史清单交叠」同类，**不是本任务清单不一致**；按先例未代为订正他任务台账。

提交后口径（无参数实跑，基线默认 HEAD=台账收口提交）：工作树除既有未跟踪 `.trae/` 外干净，改动集与回传清单无交叠 → 视为已收口（判据 B 口径）；判据 A 两件套齐全。实际退出码与提交哈希由任务回传。
