# 提案：判别生产 Hikari 池（默认 10）下 relay 批内并发的 S_prod(N)（TASK-161）

## Why

TASK-156 已实测 relay `markSent` 的并发标度 S(2)=1.8612 / S(4)=3.3066 / S(8)=5.7056（随 N 单调不减），但它用的是**每线程一条独立 `DriverManager` 自动提交连接（完全无连接池）**；TASK-156 自己的 boundary 原文就承认「scratch 真库 + test-only DriverManager IT **≠** 生产 relay（Spring/Hikari + Redisson 全局 tryLock(0) 单跑）」。

生产侧硬事实：`verify-service/src/main/resources/application.yml` 的 `hikari` 段只设了 `initialization-fail-timeout: -1`、**没有 `maximum-pool-size`** ⇒ 生效值是 Hikari 默认 **10**；同文件 `rocketmq.consumer.consume-thread-min: 32 / consume-thread-max: 40`。⇒ TASK-160 落地的 N 个 relay worker **与 32~40 个消费线程争同一个 10 连接的池**。

**唯一问题：S(N) 经过一个真实的 10 连接池后还剩多少？** 这是「并发是唯一未否掉的杠杆」推理链上**唯一未验的支柱**；它便宜（test-only IT，无需四服务、无需负载、无需 MQ），且**若答案是「剩不下」，就能省掉一整轮昂贵的负载实验**。预登记算术推演（Level B，基于已提交测量、非新测量）另见任务书：默认 5000ms 下并发单独只值 +16%（5000ms 固定等待占周期 79.2%），可交付组合是「interval 下调 + 并发」；TASK-161 只回答「池会不会把 S(N) 吃掉」，**不负责也不得声称**任何吞吐收益。推演的四个假设（① `syncSend` 不并行；② 满批 rows=100；③ S(N) 能从 IT 迁移到生产（正是本轮要验的）；④ 周期 = interval + 锁内）随结果一并登记。

## What Changes

1. 冻结起点 HEAD `291e686a663e78ec7a8d1faeec14b9aeb669bca8`。隔离环境 = 既有 scratch 容器 `task131-scratch-mysql`（MySQL 8.0.46、宿主 13318）：**只 `docker start`**（不 recreate / 不改配置 / 不删卷），只读登记 `innodb_flush_log_at_trx_commit` / `sync_binlog` / `log_bin` / `version`。**只新增** schema `task161_pool_scratch`（`sql/03-verify-db.sql` 机械改名 `sed 's/verify_db/task161_pool_scratch/g'`），IT 内每条连接硬校验 `SELECT DATABASE()`；演示库 3307 零字节写入。
2. 新增 test-only 条件真库 IT `VerifyOutboxRelayPoolConcurrencyScalingMysqlIT`（`*IT` 不被 Surefire 默认收集；缺 `TASK161_IT_*` 即 assume 跳过、rc=0 但记为未覆盖不计通过）：真实 `HikariDataSource`（`setMaximumPoolSize(10)`、`setPoolName("task161-pool-it")`、其余 Hikari 默认，不设 connectionTimeout/minimumIdle/maximumLifetime）+ 真实 MyBatis `SqlSessionFactory`，经共享池每次调用 `openSession(true)` 调生产 `VerifyEventOutboxMapper#markSent` 逐字 SQL（不自写 UPDATE）。
3. 臂与轮次：M=2000 行/轮、每臂 3 轮。**A 组（J=0）** N ∈ {1,2,4,8}——已取列表下标 `i % N` 互不相交分片（与 TASK-160 生产代码同一切分法）、每 worker 从同一池取连接；**B 组（证伪臂）** N=4 且从同一 DataSource 额外签出 8 条连接全程持有（可用 10−8=2 < N=4，强制排队），J=8 必须标注为「**池占用代理（occupancy proxy）**」而非消费者行为模型。
4. 每窗口硬判据（12+3=15 个窗口，全过才算数）：`SHOW GLOBAL STATUS` 的 `Com_update` 增量精确 = 2000（前值在重置后/barrier 前、后值在 join 后/收尾 SELECT 前）、`Com_insert`/`Com_delete` = 0、收尾 SENT=2000 且 PENDING=0；会话门 = 除本 IT 自己池连接（≤10）+1 条控制连接外无外来会话（P_S `NAME='thread/sql/one_connection'` 计数 + `SHOW PROCESSLIST` 交叉核对，**排除** `event_scheduler` 与 `compress_gtid_table` 两条系统 Daemon；FOREGROUND 原始计数照记）；池指标 `HikariPoolMXBean` 四读数（active/idle/total/**threadsAwaitingConnection**）轮内最大值，**B 组自证 `getThreadsAwaitingConnection() > 0` 否则该臂作废重做**。变异红（某 worker 漏标一行被 `Com_update==2000` 断言抓住）+ 字节还原复绿（`cmp` rc=0）+ 缺变量 skipped 三档退出码实测。
5. 预注册三支裁决（**不得事后放宽**）：`S_prod(N) = 组内中位吞吐(N) / 中位吞吐(1)`（A/B 组间不得换算或相减）。**第一支**：`S_prod(4)@J=0 ≥ 2.0` 且 `S_prod(4)@J=8 ≥ 1.8` ⇒ 判「池不是硬约束」；**第二支**：任一 `≤ 1.3` ⇒ 判「池是硬约束」（且在此之前生产不得开启 `>1`）；**第三支**：介于其间、或噪声大、或非单调 ⇒ 判「证据不足」，只报数字与噪声。无论哪支：不改任何默认值、不实施、不宣称吞吐收益、不翻案 TASK-153/154、不改写 TASK-152/156 任何数字。
6. 带外调用通道（`--it` 分支结构性跑不到 verify-service 的 IT）：仓库根临时 `.mvn/maven.config` 两行（`-Dtest=...` / `-Dsurefire.failIfNoSpecifiedTests=false`），两行原文与跑前跑后 `git status --porcelain .mvn` 快照入库存证；**任何 git add/commit 之前删除**，删除后常规套件回基线（36/41/33/103/120/59/10）证明未被收窄。不用 `MAVEN_OPTS`、不改 `mvn-verify.sh`、不改任何 pom。

## Impact

- 规范：仅增加 `sport-record-verify` relay 容量判别的证据门槛（池内标度以预注册阈值裁决、池占用代理不得冒充消费者模型、跨装配数字不可比），不改可靠投递语义或主规范总稿。
- 文件：隔离 test-only IT、`docs/perf/` 报告与机器摘要、OpenSpec 三件套（纯 ADDED）、TASK-161 spec/handoff、PLAN 纯追加 1 节；原始日志在 ignored `docs/perf/data/raw/task161-*`。
- 环境：只在自有 scratch schema 内建表与种子（收尾状态如实登记），不触碰演示 `verify_db`、其他 scratch schema、数据卷；不起四服务、不跑负载、不连 RocketMQ。
- 既有 archive、`.codex/`、`.trae/`、`add-verify-degrade-status-index/` 原状保留；不 stash、不 `git add -A`、不 push/PR。

## 判定与停止条件

任一硬判据不满足（Com_update ≠ M、窗口污染、外来会话、SENT/PENDING 不闭合）→ 判 harness 有缺陷，停并回传，不得报告该臂标度。B 组 `getThreadsAwaitingConnection()` 轮内最大值全 0 → 该臂作废重做，不得拿没有压力的压力臂支撑第一支。缺变量 skipped 不得记为真库通过。裁决按预注册三支，无论哪支都不实施优化、不改默认值（`relay-send-concurrency` 保持 1）、不翻案 TASK-153/154、不改写 TASK-152/156 数字；「池不是硬约束」仅是下一步负载实验的必要条件，池内判别本身不构成任何实施授权。
