# TASK-170 handoff：单行投递逻辑重构（统一 processRow 与 sendAndCollect 为单一 sendRow）

## 1. 一句话结论

**纯结构重构已落地**：`VerifyOutboxRelay.java` 中原并存的 `processRow`（逐行标记路径）与 `sendAndCollect`（分块标记路径）两个私有方法被**彻底删除**，统一为单一 `sendRow` 主方法（五参）＋逐行模式便捷重载（三参）；四条投递路径（串行/并发 × 逐行标记/分块标记）全部改调 `sendRow`。**零生产行为变化**：offline 全量 `36/41/33/103/140/59/10` 全绿 Skipped 全 0、verify-service 140 全过、Checkstyle 由 `867` **降为 `862`**（目标文件违规 27→22，零新增）、`application.yml` 零触碰、词面门四形态 ZERO_HIT、契约门收口 rc=0。TASK-166 登记的「把两份单行语义统一为一个 `sendRow`」欠账在此闭环。

## 2. 起点全 SHA 与门禁核对（逐位一致）

- `HEAD = 037d39ab7a981383e113172d29c6a04ee50ce6d3`
- `origin/main = 9050964699c8b802ae9da22980939d78b2d31bdc`
- `git rev-list --left-right --count origin/main...main` = `0	1`
- 任务书 SHA256 = `604fe4ca299012f224b6afa754f80704e1016a542e3f17ebc501478dbf846b84`（与派发值逐位一致）
- 工作树脏项仅 `?? spec/changes/add-verify-degrade-status-index/`（既有，零触碰）＋ `?? work/mailbox/tasks/TASK-170/`

## 3. 只改清单

- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-170/spec.md
- work/mailbox/tasks/TASK-170/handoff.md

## 4. 只改清单逐项对齐与零修改声明

- 生产代码仅改白名单的 `VerifyOutboxRelay.java`：`git diff --numstat HEAD -- <该文件>` = `67	90`（1 file changed, 67 insertions(+), 90 deletions(-)）。
- 三个保护件零改动且全绿（工作树仅 Relay 一处 `M`）：`VerifyOutboxRelayTest`(19)、`VerifyOutboxRelayConcurrencyTest`(10)、`VerifyOutboxRelayBatchMarkTest`(10)；另 `VerifyOutboxRelayBatchMarkConfigTest`(4)、`VerifyOutboxRelayBatchMarkDefaultTest`(3) 亦全绿。
- `git diff --exit-code HEAD -- verify-service/src/main/resources/application.yml` rc=0（零改写；未新增任何配置键）。
- 未触碰：`pom.xml`、构建脚本、`scripts/**`、主规格 `spec/specs/sport-record-verify/spec.md`、任何测试文件（含 IT）、既有脏项 `spec/changes/add-verify-degrade-status-index/`。

## 5. G0–G8 逐门实测退出码与关键读数

- **G0 起点核对**：§2 全 SHA 逐位一致，停手条款未触发；任务书 SHA256 逐位一致。
- **G1 单行语义统一确证**：`grep -n 'processRow|sendAndCollect' VerifyOutboxRelay.java` 零命中；`private void sendRow(` 命中 2 处（L331 五参主方法、L410 三参重载，相邻满足重载声明顺序）；耗尽检查 `row.getRetryCount() != null && row.getRetryCount() >= maxRetry` 全文件恰好 1 次（L336）；实际投递出口 `verifyEventProducer.syncSend(row)` 全文件恰好 1 次（L348，另两处 `syncSend` 仅为类级/方法级 Javadoc 的 `{@link}`/`{@code}` 引用）。
- **G2 保护件零改动**：见 §4；numstat 为空，全绿。
- **G3 生产配置零改动**：`git diff --exit-code HEAD -- application.yml` rc=0。
- **G4 全量 offline 测试门**：`bash scripts/verify/mvn-verify.sh --mode=offline test` 退出码 0，`BUILD SUCCESS`，七模块 **36/41/33/103/140/59/10**（common/gateway/user/record/verify/leaderboard/mapmatch），`Failures: 0, Errors: 0, Skipped: 0` 逐模块成立。
- **G5 静态代码门**：`--mode=offline --static=verify-service` 退出码 1（预期形态），`You have 862 Checkstyle violations` ⇒ `862 ≤ 867`；目标文件 `VerifyOutboxRelay.java` 违规数 **27→22**（LineLength 18→13，Javadoc 9→9 未变），**零新增违规**。
- **G6 词面门**：正则现场从 `.github/workflows/ci.yml`（`Public docs wording self-check` 行）提取（8 个分支、26 字符 / 58 UTF-8 字节、7 条竖线；**本文件不复述该正则原文**，否则自触该门槛），四形态（default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）**全 ZERO_HIT rc=1**；正向对照（对 ci.yml 自身跑该正则）rc=0 命中。
- **G7 空白检查门**：`git diff --check` rc=0（零尾随空格）。
- **G8 契约门与台账**：在途 `bash scripts/verify/mailbox-contract.sh --open TASK-170 --baseline=037d39ab7a981383e113172d29c6a04ee50ce6d3` rc=0（判据 A 两件套 + 判据 B 清单一致）；收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=0（见 §9）。PLAN.md 追加本任务验收记录并闭环 TASK-166 欠账，21 个受保护 token 计数无一减少（见 §8）。

## 6. 重构前后对照（`VerifyOutboxRelay.java`）

- **方法结构**：前 = `processRow(row, diagEnabled, totals)`（L314-386 定义）＋ `sendAndCollect(row, chunkSize, pendingIds, diagEnabled, totals)`（L482-530 定义）；后 = `sendRow(row, chunkSize, pendingIds, diagEnabled, totals)`（L331-401 定义）＋ 便捷重载 `sendRow(row, diagEnabled, totals)`（L410-413，体内 `sendRow(row, 0, null, diagEnabled, totals)`）。
- **三段落语义逐字保持**：① 耗尽拦截 `totals.exhausted++` + 原 ERROR 日志后 return；② 发送段 `syncSend`，抛错记 `sendNanos`、`incrRetry`、`incrRetryNanos`、`totals.failed++`、原 WARN 后 return，成功亦记 `sendNanos`；③ 标记段 `pendingIds == null` 执行单行 `markSent`（记 `markNanos`、`success++`、原 INFO；抛错记 `markNanos`、`incrRetry`、`incrRetryNanos`、`failed++`、原 WARN），否则 `pendingIds.add(row.getId())` 且满 `chunkSize` 调 `flushBatchMark(pendingIds, diagEnabled, totals)`。
- **日志文本逐字不变**：耗尽 ERROR、投递失败 WARN、逐行成功 INFO 的格式串与实参与原实现完全一致（仅将原 processRow 中超过 80 字符的两条长行改用 sendAndCollect 既有的多行拼接写法，拼接结果字符串逐字节等价）。
- **调用点替换**：`relayMessages` 串行逐行（L189）、`runShard` 并发逐行（L479）→ `sendRow(row, diagEnabled, totals)`；`deliverBatchMarkSerial` 串行分块（L502）、`runBatchMarkShard` 并发分块（L632）→ `sendRow(row, chunkSize, pendingIds, diagEnabled, totals)`。
- **Javadoc 订正**：删除 TASK-166 F3 留存的「两者语义必须同步维护，存在漂移风险」告警，恢复为统一的单行语义说明；文件规模 785→762 行。

## 7. 工具链偏差登记（环境异常，非本任务引入）

- **现象**：本机 `bash` 解析到 WSL（`C:\Windows\System32\bash.exe`），其 `Ubuntu` 发行版注册为 Stopped，但 `LocalState` 目录为空、全 `LocalAppData` 搜索不到 `ext4.vhdx`，任何 bash 调用报原文：
  `...Local\Packages\CanonicalGroupLimited.Ubuntu_79rhkp1fndgsc\LocalState\ext4.vhdx ... WSL2: ...` / `错误: Bash/Service/CreateInstance/MountDisk/HCS/ERROR_FILE_NOT_FOUND`。
- **处置**：改用本机 Git Bash（`MINGW64_NT-10.0`，`D:\git\Git\bin\bash.exe`）执行**同一条** `scripts/verify/*.sh` 入口；任务书门禁命令与四形态一字未改。此为 TASK-168 handoff §7 已登记的同源工具链（git-bash），非本任务引入。
- **次生现象**：Git Bash 直连宿主 stdout 报 `bad file descriptor`，故所有入口输出重定向落盘后再读取，退出码以 `$LASTEXITCODE` 采集；不影响入口内部判定与退出码语义。

## 8. PLAN 21 个受保护 token（scope = `work/mailbox/PLAN.md` 行命中数，base = 收口前）

`13.4=14`、`18.0=16`、`73.93=15`、`68.8=11`、`6315=12`、`1.8612=11`、`3.3066=11`、`5.7056=11`、`9.408=11`、`36525962432=11`、`36586847965=10`、`36438897772=11`、`36399582548=10`、`36098038547=10`、`2806=17`、`598=10`、`36736221648=9`、`36808102571=4`、`36821040708=2`、`36845152965=1`、`36871294588=1`（与任务书 §5 基线逐位一致；收口追加本记录后无一减少，见 PLAN.md 复测）。

## 9. 收口硬条

- 收口 offline 复跑 rc=0、七模块 `36/41/33/103/140/59/10`、Skipped 全 0、`BUILD SUCCESS`。
- 收口静态门 rc=1（预期形态）且 `862 ≤ 867`。
- 收口词面门四形态 ZERO_HIT rc=1。
- 收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=0（TASK-170 足迹已入库，视为已收口）。
- 逐路径 `git add`（无 `git add -A`/`add .`），未 push、未建 PR、未 stash。

## 10. 未覆盖项与不得推出的结论

- spotbugs / pmd 未覆盖（被 checkstyle 前后置阻断）；`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（本任务不 push）。
- 本任务为纯结构重构，**不得**据此声称任何吞吐/延迟收益、不得开启任何生产开关、不翻案 TASK-153/154/162/163/164/165/166/168/169 任何既定数字。
- 验证在 Git Bash（非 WSL）下执行（见 §7）。