# TASK-155：口径与规格一致性批（修陈旧/失效断言 + 止未跟踪噪声；与 relay 方向无关、不改运行行为）

## 目标与基线

开工 HEAD `6016d920ac473d5ccfbd1e862ef481701ba0233e`（执行 agent 开工先 `git rev-parse HEAD` 核对一致并把全 SHA 记进 handoff；不一致即停并回传，不得擅自 rebase/checkout/pull）。

本批**只修会误导下一个执行 agent 的陈旧或失效断言、并把未跟踪噪声收进 .gitignore**，**不改任何运行行为**：不动 relay 周期/批次/并发/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 等任何默认值或生产逻辑；唯一的生产文件改动是 `VerifyOutboxRelay.java` 的**类注释文字**（不改任何代码语句、签名、注解、默认值）。

**明确不在本批范围**（另开 TASK-156 紧授权处理，本批不得顺手做）：把 `wire-verify-outbox` / `adopt-native-mq-retry` / `fix-verify-outbox-poison-head-of-line` 的 spec-delta 合并进主规格、提交那 6 个归档删除侧文件与 `spec/changes/archive/*` 未跟踪目录、处置两个 0/5 在途目录与 `spec/changes/add-verify-degrade-status-index/`。理由：合并需把约 178 行 delta 放进无 outbox 段的 2699 行主规格并去重定位，属判断密集；且**只提交归档移名而不合并 delta 会违反主规格 L47 自有不变量**（「各提案 spec-delta 中 ADDED 需求已全部合并进本规范」），记录「已归档未合并」反而误导后人。故本批保持这些脏项**原状不动**。

## 边界与判据

只做以下四件，逐件可独立验收；任一件需要超出所列改动即停并回传，不得擅自扩范围。

### (b) `work/mailbox/PLAN.md`「当前进度」一句话订正

- 现状（陈旧、与其下 40+ 条验收记录直接矛盾）：第 3~4 行 `## 当前进度` 标题下的「一句话：…」整行（撤回判定口径）。
- 改为（**只替换该「一句话：…」整行**，保留 `## 当前进度` 标题与其后 `## 收口清单` 等全部内容不动）：

  > 一句话：本地 `main` HEAD `6016d92` 领先 `origin/main` **38 笔**（TASK-138~154，全部「未达外部门槛」、无 CI、未推送、无异地副本）；outbox relay 投递吞吐已定量闭合——净投递 ≈13.4 行/s（默认 5000ms）、调参至 500ms ≈37 行/s 仍 < 到达率 59~96 行/s，每行一次自动提交=一次持久化往返（TASK-152：18.0 ms/行、其中 MySQL 服务端语句事件 73.93%），批末统一标记 SENT 与线程等待归因均 NO-GO（TASK-153/154）；唯一未测杠杆=并发标度判别（方向待用户授权）。下方为逐条验收记录，最近一条为 TASK-154。

- 判据：`rg -n "撤回本会话此前" work/mailbox/PLAN.md` **0 命中**；新句含「38 笔」「13.4 行/s」「TASK-154」三词。

### (c1) `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` 类注释失效断言订正

- 现状（L29~30，失效）：`事件到达延迟由本 relay 周期（默认 5s）决定` —— 与 TASK-143 实测矛盾（同 run `callback→SENT` P50 68.8s ≫ 5s 周期，积压主导）。
- 改为（**仅改这两行注释文字**，把该 `<p>…</p>` 块逐字替换为下面三行，不动任何代码、注解、import、默认值）：

      * <p>判定链路不直发（见 VerifyOutboxService）：无积压时事件到达延迟下限由本 relay 周期（默认 5s）决定；
      * 但持续到达率超过 relay 净投递吞吐时延迟由积压主导（实测同 run callback→SENT P50 ≈68.8s ≫ 5s 周期，TASK-143），
      * 榜单侧定时结算纠偏仍是最终一致的兜底。</p>

- 判据（**强制 Maven 门槛**）：用项目约定 Git Bash 入口（`D:\git\Git\bin\bash.exe`，**不得裸用 mvn**）跑 `bash scripts/verify/mvn-verify.sh --pl verify-service`（offline）**rc=0**，且测试计数与既有基线同数（注释级改动不得改变任何用例数）。若 rc≠0 → **还原注释**、回传失败原因，不得留半改状态。

### (c2) `work/mailbox/后端优化机会总览-2026-09-26.md` §3-P2 与 §4 补 TASK-152/153/154 收口与容量上界

- 现状：§3「P2（新实测：事件链支配段）：outbox relay 投递吞吐」与 §4「建议执行顺序」第 2/3 条只更新到 **TASK-147**，仍把「下一步唯一待证因素」写成「逐行 markSent 的批内成本构成」（已被 TASK-152 测过）。
- 在 §3-P2 末尾**追加**一条（保留既有全部文字与数字，不改写历史）：

  > - **TASK-152/153/154 已收口（只测量/只裁决，未优化、未改任何默认值）**：TASK-152 管理员窗口同窗测得每行 `markSent` 外层墙钟 **18.0 ms/行**（36,183 ms / 2010 行、22 批），其中 MySQL 服务端语句事件占 **73.93%**（26,750.5 ms；差额 9,432.5 ms 不命名），即**每行一次自动提交=一次持久化往返**；TASK-153 真库对照裁决「批末统一标记 SENT」为 **NO-GO**（重复投递窗口扩大 / SENT 可见性推迟 / `sent_at` 压平 / 逐行归因丢失 四项语义反对，未翻案）；TASK-154 裁决「线程等待可归因性」为 **NO-GO**（后台 `log_flusher`/`log_writer` 归属不明、`wait/synch/*` 未采集、无 `events_waits_history_long`）。**容量上界（单轮/本机/未达外部门槛）**：默认 5000ms 净投递 ≈13.4 行/s、调参至 500ms ≈37 行/s（TASK-145 臂 B，其最强反例已自陈「不能证明无持续积压」）仍 < 到达率 59~96 行/s → **调 interval/batch 已撞墙**，唯一未测杠杆=并发标度（多分区 relay worker，语义不变；最强反例=失去按 id 全局 FIFO，须先核实有无消费者依赖同 recordId 事件顺序）。

- 在 §4 第 3 条末尾**追加**一句（不改第 1/2/4 条）：

  > **TASK-152/153/154 已收口**：markSent 批内构成已测（18.0 ms/行、73.93% 服务端语句事件）、批末标记与线程等待归因均 NO-GO；relay 投递吞吐方向收敛为「并发标度判别」一条待证假设（方向 B，待用户授权），**不得**再开第 11 个 markSent 内部构成测量（TASK-154 已证：不改仪器只能产出「未覆盖」）。

- 判据：`rg -n "18.0 ms/行|73.93%|并发标度" work/mailbox/后端优化机会总览-2026-09-26.md` 命中 ≥3；既有历史数字一字未删（`rg -c "13.4 行/s" …`、`rg -c "68.8s" …`、`rg -c "6315ms" …` 三项计数均**不减**）。

### (d) `.gitignore` 收录 `.codex/`、`.trae/`

- 在「# ===== Agent 工作记忆（不入公开仓库） =====」段（现有 `.workbuddy/`、`.qoder/`）下**追加两行**：`.codex/`、`.trae/`。
- 判据：`git status --short` 中 `?? .codex/`、`?? .trae/` 两行**消失**（被忽略）；`git check-ignore .codex/ .trae/` 各返回命中。

## 证据与交付

- **不改业务行为**：除 `VerifyOutboxRelay.java` 的**注释文字**外，不改任何 `.java`/`.sql`/`.yml`/`.yaml`/`.properties` 的代码或配置；不改任何默认值；不 push、不建 PR；不用 `git stash`、不用 `git add -A`/`git add .`。
- **既有脏项原状保留**：6 个归档删除侧文件、`spec/changes/archive/{wire-verify-outbox,adopt-native-mq-retry}/`、`spec/changes/add-verify-degrade-status-index/`、两个 0/5 在途目录——**一律不触碰**（属 TASK-156）。
- **只 stage 本任务文件（逐文件 `git add <path>`）**：`work/mailbox/PLAN.md`、`verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`、`work/mailbox/后端优化机会总览-2026-09-26.md`、`.gitignore`、`work/mailbox/tasks/TASK-155/spec.md`、`work/mailbox/tasks/TASK-155/handoff.md`。
- **命令行不得出现中文**（exit 127）：提交信息写入 UTF-8 文件再 `git commit -F <file>`；建议两笔本地提交——(1) 一致性订正：`PLAN.md` + `VerifyOutboxRelay.java` + `后端优化机会总览` + `.gitignore`；(2) 台账：`TASK-155/spec.md` + `TASK-155/handoff.md`。
- **收口门槛**：`bash scripts/verify/mvn-verify.sh --pl verify-service` offline **rc=0**（c1 强制）；`git diff --check` rc=0；无参数 `bash scripts/verify/mailbox-contract.sh` —— **提交 handoff 前** rc=1（TASK-155 仅 spec=进行中，预期口径，可用 `--open TASK-155` 放行）、**两件套提交后** rc=0（足迹不在工作树）。`--mode=online`/CI 不跑，**未达外部门槛**。
- **回传**：据实填 `work/mailbox/tasks/TASK-155/handoff.md`（结论 / 起点核对 HEAD 全 SHA / 实际改动清单「只改」节逐文件列全 / 判据与退出码 / 未覆盖与不得推出）。本批**不新建 OpenSpec 三件套**（不是能力变更，是一致性订正）。
