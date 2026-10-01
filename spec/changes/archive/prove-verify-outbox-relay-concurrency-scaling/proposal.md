# 提案：判别 outbox relay `markSent` 并发标度（TASK-156）

## Why

relay 容量方向已收敛：TASK-152 测得每行 `markSent` = 18.0 ms/行（其中 MySQL 服务端语句事件 73.93%），即「每行一次自动提交 = 一次持久化往返」；TASK-153 裁决「批末统一标记 SENT」NO-GO（重复投递窗口 / 可见性 / `sent_at` / 归因四类语义反对）；TASK-154 裁决线程等待不可归因。调 interval/batch 已撞墙（默认 5000ms 净投递 ≈13.4 行/s、调至 500ms ≈37 行/s 仍 < 到达率 59~96 行/s）。**唯一未测的杠杆 = 并发标度**：在逐行语义完全不变的前提下把 `markSent` 改为 N 路并发，InnoDB 组提交（`innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` 的 binlog group commit）能否摊薄每行一次持久化往返的成本——TASK-141 测的是整系统连接池 10 vs 20，不是 `markSent` 的并发标度。此问题不裁决，relay 容量决策（放宽语义批量标记 vs 接受吞吐上界 vs 分区 relay）无法收敛。

## What Changes

1. 冻结 HEAD `7eb609501730729d7f0a69f2bd31d4df88a04ead`、既有脏项与 TASK-152/153/154 结论。隔离环境 = 既有 scratch 容器 `task131-scratch-mysql`（MySQL 8.0.46、宿主 13318），**只新增** schema `task156_concurrency_scratch`（仓库 `sql/03-verify-db.sql` 机械改名）；只读登记 `innodb_flush_log_at_trx_commit`、`sync_binlog`、binlog 开关；不改 MySQL 配置/仪器、不重置计数器、不重启/重建容器。
2. 新增 test-only 条件真库 IT（`*IT` 不被 Surefire 默认收集；缺 `TASK156_IT_*` 即 assume 跳过且不记通过）：SQL 逐字取自生产 `VerifyEventOutboxMapper#markSent` 注解渲染，每线程独立 `DriverManager` 连接、autoCommit=true；种子 M=2000 行 PENDING；臂 N ∈ {1,2,4,8} × 3 轮，按 `id % N` 互不相交分片；barrier 起跑、join 收尾；每臂读总墙钟、聚合吞吐、单行墙钟分布、`Com_update` 前后差（`SHOW GLOBAL STATUS`——本实例 P_S `global_status` 不列 Com_*）与目标 digest 计数（若可读）。
3. 硬判据（任一不满足即判 harness 缺陷、停并回传，不得报告该臂标度）：每轮 `Com_update` 增量精确 = M（前值在重置 PENDING 之后、barrier 之前取，后值在最后线程 join 之后、任何收尾 SELECT 之前取）；窗口内 `Com_insert`/`Com_delete` = 0；收尾 SENT=M 且 PENDING=0；起跑前记录在跑会话（P_S 客户会话线程计数 + `SHOW PROCESSLIST`），要求除本臂自有 N+1 条连接外无外来前台会话。变异红（漏标/重复标记一行）证明 IT 非空过，还原复绿，三档退出码（红 1 / 复绿 0 / 缺变量 skipped 0）实测记录。
4. 预注册三支裁决（不得事后放宽）：S(N)=throughput(N)/throughput(1)（同实例同 M 各臂中位）；S(8) ≥ 2.0 且单调不减 → 「并发标度成立」（仅必要条件）；S(8) ≤ 1.2 → 「每行提交成本是硬墙」；其间或噪声大/非单调 → 「证据不足」。无论哪支：不改任何默认值、不实现分区 relay、不宣称生产吞吐收益。
5. FIFO 反例只读核查：两个消费者（`VerifyEventConsumer`、`LeaderboardEventConsumer`）是否存在同 recordId 事件顺序依赖（初判 → 申诉终判改判）。存在 → 记录「未来分区 relay 必须按 recordId 分区」的约束；不存在 → 记录依据（消费端 SETNX/锚点幂等使顺序无关），并声明消费端幂等不自动等于授权改 relay。不据此改任何代码。
6. 带外调用通道：`scripts/verify/mvn-verify.sh` 无 `-D` 透传、`--it` 硬编码 leaderboard-service，故经仓库根 `.mvn/maven.config`（两行 `-Dtest=...` / `-Dsurefire.failIfNoSpecifiedTests=false`）给出目标类选择器；该文件不在 `.gitignore` 内，跑前跑后 `git status --porcelain .mvn` 与原文存证，**任何 git add/commit 之前删除**；常规套件档在删除之后跑（应回基线 common 36 / verify-service 110、skipped 0）。不用 `MAVEN_OPTS` 当通道、不改 `mvn-verify.sh` 或任何 pom。

## Impact

- 规范：仅增加 `sport-record-verify` relay 容量判别的证据门槛，不改可靠投递语义或主规范总稿。
- 文件：隔离 test-only IT、`docs/perf/` 报告与机器摘要、OpenSpec 三件套、TASK-156 spec/handoff、PLAN/机会总览订正与追加；原始日志在 ignored `docs/perf/data/raw/task156-*`。
- 环境：只在自有 scratch schema 内建表与种子（收尾留存 2000 行 SENT 作为明确标注的运行证据），不触碰演示 `verify_db`、其他 scratch schema、数据卷；不起四服务、不跑 c100×2000。
- 既有 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/` 原状保留；不 stash、不 `git add -A`、不 push/PR。

## 判定与停止条件

任一硬判据不满足（Com_update ≠ M、窗口污染、外来会话、SENT/PENDING 不闭合）→ 判 harness 有缺陷，停并回传，不得报告该臂标度。N=1 单行墙钟 <1ms 或报错 → 先查 harness/连接语义再信 N>1。裁决按预注册三支，无论哪支都不实施优化、不改默认值、不翻案 TASK-153、不改写 TASK-152 数字；「并发标度成立」仅是必要条件，分区 relay 的实施（含按 recordId 分区与 relay 锁/多实例语义）须另立提案与授权。
