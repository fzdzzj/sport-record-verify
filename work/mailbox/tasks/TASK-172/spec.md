# TASK-172 spec：将分块批量标记提案 add-verify-outbox-relay-batch-mark 并入主规格并归档（第二轮规格收敛）

## 0. 硬约束与红线（继承 TASK-158/159/165/166/167 §0，逐字适用）

1. 本任务书是唯一权威。开工先逐位核对 §3 开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写临时脚本文件再执行；提交信息一律使用 UTF-8 无 BOM 文件配合 `git commit -F <file>`。
3. bash 一律写成 `.sh`（或 python `.py`）文件再用 `D:\git\Git\bin\bash.exe <路径>` / `python <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；严禁裸 mvn、严禁并发执行任何 Maven 命令（Maven 命令必须严格串行执行）。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 必须由用户显式单次授权。
6. **本轮零生产行为变化、纯文档与规格收敛**：不改动任何 `.java`/`.kt`/`.yml`/`.properties`/`.sql`/pom/`scripts/**`/`docs/**` 文件；不跑性能负载、不起四服务、不碰 Docker（`sport-verify-mysql`、`task131-scratch-mysql` 等一律零触碰）。
7. **零触碰名单**：`spec/changes/add-verify-degrade-status-index/`（未跟踪目录，任何任务不得收编）；其余 5 个在途未定/测量提案目录（`measure-verify-outbox-mark-sent-server-event`、`prove-verify-outbox-relay-concurrency-pool-drain`、`prove-verify-outbox-relay-interval-repeatable`、`resume-verify-outbox-mark-sent-server-event`、`update-verify-outbox-relay-delay`）本轮零触碰。
8. 不翻案、不改写任何已入库结论与历史数字（TASK-143~171）。
9. `work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与顶端外部门槛叙事）。
10. 词面门红线：**任何要入库的文档都不得原样内嵌词面门的正则字面量或敏感词**。

---

## 1. 本轮唯一目标与任务背景

### 1.1 背景
- 在 TASK-165 中，我们在产品授权下建立了在途提案 `spec/changes/add-verify-outbox-relay-batch-mark`，定义了分块批量标记（chunk 25）的 5 条被授权语义变化与上界；
- 在 TASK-166 中，修复了 F1/F2/F3 并由真库 IT 确证了 4 项语义上界（Level A 证据）；
- 在 TASK-169 中，通过 3 对交错稳态判别，分块批量标记正式在生产落地生效（`verify.outbox.relay-batch-mark-enabled: true`，单行标记耗时降 91%，吞吐翻 4.87 倍）；
- 在 TASK-170 中，完成了单行投递逻辑重构，彻底消除 `processRow` 与 `sendAndCollect` 双路径分叉，统一为单一 `sendRow`；
- 在 TASK-171 中，在分块标记落地态之上进一步落地了批内并发 $N=2$（`relay-send-concurrency: 2`），单行锁内墙钟进一步砍半至 2.01 ms，锁内吞吐突破 500 行/s，并达成第十一次外部门槛（run `36958994260`）；
- 此时，该提案所承载的功能与配置已在生产完全生效、单测与 IT 全绿，但在途目录 `spec/changes/add-verify-outbox-relay-batch-mark` 仍处于在途状态，主规格 `spec.md` 尚未并入该需求。

### 1.2 目标（四件套，缺一不可收口）
1. **主规格并入**：将 `add-verify-outbox-relay-batch-mark` 的规范需求并入 `spec/specs/sport-record-verify/spec.md`；
2. **头部清单与不变式维护**：主规格头部已归档清单追加 `add-verify-outbox-relay-batch-mark`（条数由 58 增至 59），archive 目录迁移后由 60 增至 61，严格保持 `archive 目录数 - 头部清单条数 = 恰 2 项合法例外` 的全局不变式；
3. **提案目录闭环与归档**：更新提案的 `tasks.json`（将 TASK-169/171 已落地的步骤标为 completed，追加归档阶段任务，使 `allPass=True`），通过 `git mv` 将目录移入 `spec/changes/archive/add-verify-outbox-relay-batch-mark/`；
4. **全项门禁与台账收口**：离线测试全模块全绿（`36/41/33/103/143/59/10`）、Checkstyle 严格维持 862、词面门四形态 ZERO_HIT、契约门两件套通过、22 个受保护 tokens 只增不减、`PLAN.md` 纯追加验收记录。

---

## 2. 主规格（spec.md）修改细节与锚点

### 2.1 头部清单追加（第 59 项）
在 `spec/specs/sport-record-verify/spec.md` 的 `## 本规范已归档以下提案` 列表末尾（当前第 65 行 `- shorten-submit-db-footprint（...）` 之后）追加一行：
```markdown
- add-verify-outbox-relay-batch-mark（outbox relay 可选有界分块标记 SENT，默认开启）
```
头部清单条数由 58 变为 **59**。

### 2.2 需求正文插入（锚点定位）
在 `### Requirement: 规则阈值可配置`（约 L1043）与 `### Requirement: outbox relay 批内并发投递默认关闭且串行路径与引入前等价`（约 L1061）之间，插入分块批量标记的权威 Requirements：

```markdown
### Requirement: outbox relay 有界分块标记 SENT 默认开启

WHEN 未显式配置或配置 `verify.outbox.relay-batch-mark-enabled=true`（生产默认 `true`），
系统 SHALL 对已成功发送行的 SENT 标记按 chunk（`verify.outbox.relay-batch-mark-chunk-size`，生产默认 `25`，钳位 `[1, batch-size]`）合并为一条批量条件 UPDATE，更新条件 MUST 保持 `status = 'PENDING'`（幂等）。系统被显式授权以下五项语义变化及其上界：

1. **重复投递窗口上界**：进程异常崩溃时，已由 broker 成功接收但尚未执行 chunk 标记的事件行数上界为 **chunk-size**（逐行路径为 1，TASK-153 候选为 batch-size=100）。下轮重投时 MUST 沿用行内原有 `eventId`，使消费端去重键稳定。
2. **SENT 可见性延迟上界**：独立连接可见已成功发送事件为 SENT 的延迟上界为一个 chunk 的发送时长（逐行路径为每行发送返回立即独立提交可见）。
3. **`sent_at` 时间语义**：同 chunk 内所有行的 `sent_at` 统一记录为该 chunk 批量 UPDATE 执行时的数据库时间（`NOW()`），**chunk 内同值**；该值与 DDL 注释「投递成功时间」的偏差被本规格显式授权，且代码 javadoc 与日志 MUST 予以披露。
4. **标记失败重投语义**：chunk 批量标记 SQL 真失败（抛出持久化异常）时，该 chunk 内已投递行**全部保留 PENDING**、下轮整块重投；系统 MUST 对该 chunk 内的每个 id 各调用一次 `incrRetry` 以保持失败计数与重试耗尽判定语义，且异常 MUST NOT 外逃打断整轮 relay。
5. **成功行逐行 INFO 日志被 chunk 级日志取代**：开启态下成功行不再有逐行 INFO 日志（原 `outbox 事件投递成功：id=…, eventId=…, tag=…` 由每 chunk 一条汇总日志取代）；失败行的逐行 WARN 日志**保持不变**。

#### Scenario: 成功行按 chunk 分块批量标记
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且 `relay-batch-mark-chunk-size=25`
WHEN relay 取出一批 100 行待投递事件且全部发送成功
THEN 恰好调用 4 次 `markSentBatch`，每次传入 25 个有序 ID，条件含 `status = 'PENDING'`
AND 每次批量更新返回后 chunk 内行状态在独立连接上可见为 SENT

#### Scenario: 进程崩溃后重复投递有界收窄
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且 `relay-batch-mark-chunk-size=25`
WHEN chunk 内前 k 行（k <= 25）发送成功后进程崩溃或退出
THEN 下轮 `selectPendingBatch` 重投行数上界为 25（收窄自 batch-size 100）
AND 重投行的 `eventId` 与崩溃前逐字一致

#### Scenario: 标记 SQL 异常补偿失败计数
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且某 chunk 包含 n 行已发送事件
WHEN 该 chunk 执行 `markSentBatch` 抛出 SQL 异常
THEN 该 chunk 全部 n 行保持 PENDING 状态
AND 系统对该 chunk 内每个 ID 逐一调用一次 `incrRetry`
AND totals.failed 增加 n，异常被隔离，后续 chunk 继续处理

### Requirement: outbox relay 分块标记关闭路径与逐行等价保留

WHEN 显式配置 `verify.outbox.relay-batch-mark-enabled=false` 时，
系统 SHALL 保持向后兼容的逐行标记模式，relay MUST NOT 调用 `markSentBatch`，调用序列与引入分块标记前**逐字等价**；单行处理与并发路径统一由单一 `sendRow` 承载。

#### Scenario: 显式关闭保持逐行等价
GIVEN `verify.outbox.relay-batch-mark-enabled=false`
WHEN relay 处理一批待投递事件
THEN 行为与引入分块标记前逐字等价：每行发送成功立即调用 `markSent` 独立提交，崩溃重复投递窗口至多 1 行
```

### 2.3 变更历史追加
在 `spec/specs/sport-record-verify/spec.md` 末尾的 `## 变更历史` 列表中追加条目：
```markdown
- **add-verify-outbox-relay-batch-mark**：outbox relay 可选有界分块标记 SENT 能力域（TASK-165 建立、TASK-166 真实 MySQL 确证四项上界、TASK-169 三对交错稳态判别落地为生产默认开启、TASK-170 单行投递重构为单一 sendRow、TASK-171 在此基础上叠加批内并发 N=2 生产落地）。在产品决策显式授权下接受崩溃时重投窗口有界扩大至 chunk-size（25，收窄自 TASK-153 候选的 batch-size 100），单行标记耗时由 16.65ms 降至 1.50ms（降 91%），彻底消除数据库写串行化瓶颈。引用变更 spec/changes/archive/add-verify-outbox-relay-batch-mark/。
```

---

## 3. 开工读数与基线

- **HEAD**：`732c2b9a2b9bfc6713c99a443ced93d8e34a4f5c`
- **origin/main**：`763b837fb039f103e1428c9a3c2844673bec132d`
- **`git rev-list --left-right --count origin/main...main`**：`0 1`
- **工作树未跟踪项**：仅 `spec/changes/add-verify-degrade-status-index/`（严禁触碰）
- **主规格行数**：`3569` 行，`### Requirement:` 命中恰 **161** 处
- **在途提案数**：6 个（`add-verify-outbox-relay-batch-mark` 归档后变为 5 个 tracked + 1 个 untracked）
- **archive 提案数**：60 个（归档后变为 **61** 个）
- **全量离线测试基线**：七模块 `36 / 41 / 33 / 103 / 143 / 59 / 10`，退出码 `0`，`BUILD SUCCESS`，Skipped 全 `0`
- **静态检查基线**：`verify-service` 静态检查 Checkstyle violations 严格为 **862** 处
- **词面门基线**：四形态（default, `LC_ALL=C`, `zh_CN.UTF-8`, `C.UTF-8`）均为 `ZERO_HIT rc=1`
- **契约门基线**：收口无参模式退出码 `0`
- **PLAN.md 22 个受保护 tokens 基线**（行命中数不得减少）：
  `13.4=16`、`18.0=18`、`73.93=17`、`68.8=13`、`6315=14`、`1.8612=13`、`3.3066=13`、`5.7056=13`、`9.408=13`、`36525962432=13`、`36586847965=12`、`36438897772=13`、`36399582548=12`、`36098038547=12`、`2806=19`、`598=12`、`36736221648=11`、`36808102571=6`、`36821040708=4`、`36845152965=3`、`36871294588=3`、`36880083885=7`（另第十一次外部门槛 `36958994260=4`）。

---

## 4. 提交规划与交付物

- **C-01（规格并入与提案归档）**：
  `docs(spec): 将在途提案 add-verify-outbox-relay-batch-mark 并入主规格并归档（TASK-172）`
  - 修改 `spec/specs/sport-record-verify/spec.md`（头部清单 +1、插入 2 个 Requirement、变更历史 +1 条目）；
  - 更新 `spec/changes/add-verify-outbox-relay-batch-mark/tasks.json`（Task 5 标记 completed，追加 Task 7 归档完成，`allPass=True`）；
  - `git mv spec/changes/add-verify-outbox-relay-batch-mark spec/changes/archive/add-verify-outbox-relay-batch-mark`。
- **C-02（台账与两件套入库）**：
  `docs(mailbox): 登记 TASK-172 验收记录与任务两件套`
  - `work/mailbox/PLAN.md` 纯追加 TASK-172 验收记录；
  - 入库 `work/mailbox/tasks/TASK-172/spec.md` 与 `handoff.md`。
