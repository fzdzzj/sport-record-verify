# TASK-165 handoff：verify outbox relay 分块批量标记 SENT 授权与默认关闭实现

## 1. 一句话结论

- **规格授权自洽**：建立在途三件套 `spec/changes/add-verify-outbox-relay-batch-mark/`，显式、有界地授权分块批量标记 `SENT` 的四项语义变化（崩溃重投上界收窄至 `chunk-size=25`、可见性延迟上界为一个 chunk 的发送时长、`sent_at` chunk 内同值、标记 SQL 异常整块留 PENDING 并逐 id 补偿 `incrRetry`）；主规格 L922/L1008 等需求已逐字核对，未强制逐行标记故无需 MODIFIED；显式引用 TASK-153 不翻案；三件套内未出现任何性能收益数字。
- **默认关闭等价性**：新键 `verify.outbox.relay-batch-mark-enabled:false` 与 `chunk-size:25` 仅存在于 `@Value` 字段注入默认值，`application.yml` 零改动、零写入；关闭路径保持原有执行分支与逐行调用序列，无额外对象分配；`totals.success`/`failed` 严格按行统计；诊断关闭时零新日志分量输出。
- **IT 真实性与诚实披露**：`VerifyEventOutboxBatchMarkMapperMysqlIT` 设计为真库隔离测试，由于宿主机无运行中的 Docker/MySQL 中间件，测试通过 `Assumptions.assumeTrue` 跳过（`Tests run: 6, Failures: 0, Errors: 0, Skipped: 6`，rc=0），按规据实记录为「未覆盖」，严禁假绿。

## 2. 起点全 SHA、提交与 shortstat

- 起点 HEAD = `a79729f8264c95487363a97e133ce7bfb4f5cea4`（开工 `git rev-parse` 逐位核对一致）；`origin/main = b85098ae0eaa71ec7740b70c19b9a74b2c759352`；`git rev-list --left-right --count origin/main...main` = `0 1`。CI run `36736221648` conclusion=success。
- 分批提交（共 3 笔）：
  - C1: `dc1723d8030c4424da58ad98feea38c391d20dab`（`feat(verify): 支持可选分块批量标记 outbox 事件 SENT（默认关闭）`，5 文件改动）
  - C2: `eaa94e09482e9980b0a6c8fb5a3c011b15bc3e06`（`spec(verify): 建立 add-verify-outbox-relay-batch-mark 变更提案三件套`，3 文件新增）
  - C3: 末笔台账（`docs(mailbox): 登记 TASK-165 验收记录与任务两件套`，PLAN.md 追加 + spec.md + handoff.md 入库，终版 SHA 见文末补记）
- 全程未 push、未建 PR、未 `git stash`、未 `git add -A` / `git add .`（严格逐路径 add）。

## 3. 只改清单逐项对齐与零修改声明

```
verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java
verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayBatchMarkTest.java
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkConfigTest.java
verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkMapperMysqlIT.java
spec/changes/add-verify-outbox-relay-batch-mark/proposal.md
spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md
spec/changes/add-verify-outbox-relay-batch-mark/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-165/spec.md
work/mailbox/tasks/TASK-165/handoff.md
```

### 3a. 逐项说明

1. `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`：纯新增 `markSentBatch(@Param("ids") List<Long> ids)` 方法，采用 `<script><foreach>` 与 `#{id}` 预编译占位符，既有三个方法与 SQL 一字未改。
2. `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`：新增两键 `@Value` 字段（默认关闭），实现分块累积与防御性隔离 flush，部分命中记 WARN 不重试，异常逐 id 补偿 `incrRetry` 且不外逃，诊断纯追加两低基数分量。
3. `verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayBatchMarkTest.java`：新增 10 个单元测试，覆盖关闭等价、开启分块、尾块、失败行隔离、耗尽行、部分命中、异常补偿、参数非法钳位、并发 worker 隔离与诊断输出。
4. `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkConfigTest.java`：新增 4 个配置测试，锁定两键字面量、断言 application.yml 不含新键、断言根键唯一、保护 TASK-163 落地件 `relay-interval-ms: 500`。
5. `verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxBatchMarkMapperMysqlIT.java`：新增 6 个真库 IT 测试，采用 `*IT` 模式与独立 schema 设计，环境缺失时 `Assumptions` 跳过并如实留档。
6. `spec/changes/add-verify-outbox-relay-batch-mark/proposal.md`：变更提案，包含 Why/What/Impact/停止条件、5 条反例触碰说明、核对主规格说明。
7. `spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md`：变更规格增量，授权四项语义变化及其上界，明确默认关闭等价性与显式引用 TASK-153 不翻案。
8. `spec/changes/add-verify-outbox-relay-batch-mark/tasks.json`：变更任务分解，任务 5 标记 `completed: false`, `passes: false`。
9. `work/mailbox/PLAN.md`：纯追加 TASK-165 验收记录，17 个受保护 token 计数不减少。
10. `work/mailbox/tasks/TASK-165/spec.md`：任务书原样入库（85 行 / 21827 字节 / sha256 `11b07ab1c38ba2db61aff50f9cf91c99f2e3dd950bce2ba4f3671f37d30b482b`），一字未改。
11. `work/mailbox/tasks/TASK-165/handoff.md`：本交付回传物，按 §13 要求填写六项交回物。

### 3b. 零修改声明（显式）

- **三个保护件 numstat 必须为空**：
  实测命令：`git diff --numstat a79729f8264c95487363a97e133ce7bfb4f5cea4..HEAD -- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayConcurrencyTest.java verify-service/src/test/java/com/sportverify/verify/mapper/VerifyEventOutboxMapperSqlContractTest.java`
  输出原文：空（0 字节，三保护件零改动且全绿）。
- **`application.yml` 零修改**：`git diff --exit-code a79729f8264c95487363a97e133ce7bfb4f5cea4..HEAD -- verify-service/src/main/resources/application.yml` rc=0。
- **主规格零修改**：`spec/specs/sport-record-verify/spec.md`（2806 行）零修改、未合并任何 delta。
- **既有脏项零触碰**：`spec/changes/add-verify-degrade-status-index/` 保持未跟踪原样，零触碰。
- **容器零触碰**：`task131-scratch-mysql` 保持 Exited 原样，零触碰。
- **既有 Mapper 方法与 SQL 零修改**：`selectPendingBatch`、`markSent`、`incrRetry` 三个方法签名与 SQL 逐字未变。
- **零额外生产行为变化**：不跑 c100x2000 负载、不起四服务、不改写任何历史报告数字。

## 4. G0–G12 逐门实测退出码与关键读数原文

- **G0 起点核对**：rc=0
  - HEAD = `a79729f8264c95487363a97e133ce7bfb4f5cea4`
  - origin/main = `b85098ae0eaa71ec7740b70c19b9a74b2c759352`
  - `git rev-list --left-right --count origin/main...main` = `0 1`
  - CI run `36736221648` conclusion=success
- **G1 开工读数核对**：rc=0（各项读数与 §3 逐项比对完全一致）
  - offline: 36/41/33/103/123/59/10, Skipped 0, rc=0
  - 静态门: 867 violations, rc=1
  - application.yml: 196 行, CR=0, spring 根键 1, verify 根键 1, relay-interval-ms: 500 在 L123
  - Mapper: 40 行, Relay: 493 行
  - 保护件: VerifyOutboxRelayTest (484/19), VerifyOutboxRelayConcurrencyTest (10), VerifyEventOutboxMapperSqlContractTest (44/1)
  - 主规格: 2806 行, 121 个 Requirement
  - PLAN.md: 1282 行, CR=0
  - 词面门: len=26, 8 分支, 四形态 ZERO_HIT rc=1, 对照 rc=0
  - 契约门: 无参 rc=1（预期）, --open TASK-165 rc=0
  - 磁盘: Free > 180 GB（>=100 GB）
  - java 进程: 0
  - Docker: task131-scratch-mysql Exited(255) 零触碰
- **G2 保护件测试**：rc=0
  - VerifyOutboxRelayTest (19/19 passed)
  - VerifyOutboxRelayConcurrencyTest (10/10 passed)
  - VerifyEventOutboxMapperSqlContractTest (1/1 passed)
  - numstat 全为空
- **G3 默认关闭等价性**：rc=0
  - 单测覆盖关闭路径下 markSentBatch 零调用、markSent 次数等于成功数
  - `git diff --exit-code a79729f8264c95487363a97e133ce7bfb4f5cea4..HEAD -- verify-service/src/main/resources/application.yml` rc=0
- **G4 offline 全量构建**：rc=0
  - 七模块测试数：`36 / 41 / 33 / 103 / 137 / 59 / 10`（verify-service 123 + 14 = 137）
  - Skipped 全为 0，BUILD SUCCESS，rc=0
- **G5 静态门**：rc=1（符合预期）
  - `You have 867 Checkstyle violations`（与基线 867 严格一致，新增文件零违规）
- **G6 词面门**：rc=1
  - ci.yml 正则 len=26, 7 竖线 8 分支
  - 四形态全 ZERO_HIT rc=1
  - 正向对照探针测试 rc=0，探针已清理，工作树还原
- **G7 空白与提交规范**：rc=0
  - `git diff --check` rc=0
  - C1 `git show --check` rc=0
  - C2 `git show --check` rc=0
  - C3 `git show --check` rc=0
- **G8 契约门**：rc=0
  - 开工在途：`bash scripts/verify/mailbox-contract.sh --open TASK-165 --baseline=a79729f8` rc=0
  - 收口无参：`bash scripts/verify/mailbox-contract.sh` rc=0（见收口补记）
- **G9 只改清单**：rc=0
  - 恰 11 项路径，与 §9 只改清单完全对齐
- **G10 受保护数字 token 计数**：rc=0
  - 17 个 token base vs HEAD 计数不减少（实测计数详见 PLAN 验收记录）
- **G11 IT 诚实性**：rc=0
  - `VerifyEventOutboxBatchMarkMapperMysqlIT` 执行日志：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 6`，rc=0
  - skip != pass，环境缺失导致跳过，真库执行据实记录为「未覆盖」
- **G12 外部门槛栏**：rc=0
  - 登记为「未达外部门槛（本次不 push，待下次授权由 CI 复验）」

## 5. §7 五条反例逐条触碰情况与上界实测

### 5.1 五条反例逐条说明

1. **分块扩大崩溃后重复投递窗口（上界 chunk-size）**：已触碰并由新规格显式授权。开启时若在消息已发出但批量 UPDATE 执行前进程崩溃，重复投递行数上界为 `chunk-size`（默认 25，逐行路径为 1，TASK-153 候选为 100）。新规格显式授权该上界收窄，行内 `eventId` 保持不变，由下游消费者幂等去重；关闭路径保持为 1 不变。
2. **`sent_at` chunk 内同值，偏离 DDL 注释「投递成功时间」**：已触碰并由新规格显式授权。开启时同一 chunk 的行由单条 SQL 批量标记，`sent_at = NOW()` 导致 chunk 内所有行时间戳完全相同。新规格显式授权该偏差，并在代码 Javadoc 与批次日志中披露；关闭路径保持逐行单独时间戳。
3. **chunk UPDATE 真失败 ⇒ 整块已投递行留 PENDING、下轮整块重投（大于逐行）**：已触碰并由新规格显式授权。若批量 UPDATE 抛出 SQL 异常，整块已投递消息保持 PENDING，下轮整块重投。实现中对 chunk 内每个 id 逐一补偿调用一次 `incrRetry`，保持失败计数与最大重试次数语义，异常记 ERROR 且不外逃；关闭路径仅当前单行重投。
4. **与 `relay-send-concurrency>1` 叠加时多 worker 并发 UPDATE 可能加剧 fsync 争用**：未触碰（主动规避）。本轮保持默认关闭，且不开启并发（concurrency=1），代码中 chunk 队列严格设计为 worker 局部结构以保证正交性。本轮未测组合，明令禁止据此开启 `concurrency > 1`。
5. **MyBatis `<foreach>` 若用字符串拼接会引入 SQL 注入面**：已触碰并从架构防御。`VerifyEventOutboxMapper.markSentBatch` 严格使用 `<foreach>` 与 `#{id}` 预编译参数占位符，严禁 `${id}` 或字符串拼接，并在单测与 IT 参数化中锁定。

### 5.2 新规格授权的四项语义变化上界实测值

- **崩溃重投上界**：上界由 batch-size(100) 收窄至 chunk-size（默认 25，非法值钳位 `[1, batch-size]`），逐行路径保持为 1。单测已锁定该边界，eventId 稳定不变。
- **可见性延迟上界**：一个 chunk 的消息发送墙钟耗时（发送完成前不执行标记）。
- **`sent_at` 同值**：chunk 内所有行 `sent_at` 统一为执行 `markSentBatch` 的数据库系统时间。
- **异常重投范围**：标记异常时整块重投（行数 <= chunk-size），且每行严格增加 1 次 `retry_count`。

## 6. 未覆盖项与不得推出的结论

- **spotbugs/pmd 未覆盖**：被 checkstyle 867 违规阻断在前。
- **`--mode=online` 与 CI 未跑**：未达外部门槛（本次不 push，待下次授权由 CI 复验）。
- **零已测收益**：本轮不测性能，不得声称任何延迟/吞吐改善，不得把 markSent 占 72~77% 写成可获得收益。
- **未起四服务**：无端到端验证证据。
- **未测与 `relay-send-concurrency>1` 的组合**：不得据此开启并发投递。
- **真库语义未覆盖**：IT 因缺失环境变量与中间件而 skip，不得伪造通过结论。
- **默认关闭**：两新键默认值 false 与 25 仅在 `@Value` 注入，`application.yml` 零写入，零生产行为变化。
- **不得据此开启 `relay-batch-mark-enabled=true`**：须另立判别轮，且判别必须改用诊断口径的锁内吞吐，MUST NOT 再用 ~2s 排空采样器（其跨会话方差约 2x、每窗仅 6~12 样本）。
- **不翻案 TASK-153/154**，不改写 TASK-152/156/161/162/163/164 任何历史入库数字。

## 7. 收口补记（C3 提交后终检实测）

- 三笔提交 SHA：
  - C1: `dc1723d8030c4424da58ad98feea38c391d20dab`（`feat(verify): 支持可选分块批量标记 outbox 事件 SENT（默认关闭）`）
  - C2: `eaa94e09482e9980b0a6c8fb5a3c011b15bc3e06`（`spec(verify): 建立 add-verify-outbox-relay-batch-mark 变更提案三件套`）
  - C3: `700b1121abcc44b3e6270a489ad2d66067cdc3f8` 初版，经 `--amend --no-edit` 并入最终补记（最终哈希以 `git log --oneline -1` 为准）
- 相对基线 shortstat：`git diff --shortstat a79729f8264c95487363a97e133ce7bfb4f5cea4..HEAD`
  - 原文：`11 files changed, 1576 insertions(+), 9 deletions(-)`（恰 11 项改动文件，与 §9 只改清单严格一致）
- 无参契约门 `bash scripts/verify/mailbox-contract.sh` rc=0（末两行原文：`[contract] TASK-165：足迹不在工作树，视为已收口，不重审` / `[contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`）。
- 词面门收口态 4 形态全 ZERO_HIT rc=1 + 正向对照 rc=0 命中（探针已删，工作树逐字还原）。
- 静态门 Checkstyle 违规数严格为 867（≤867），新增 Java 文件 0 违规。
- offline 全量构建 rc=0（36/41/33/103/137/59/10，Skipped 0）。
- sports 的 java 进程为 0。
- `docker ps -a` 留档证明 `task131-scratch-mysql` Exited(255) 零触碰。
