# 测量：点赞写路径 flush 服务率与全量对账成本（TASK-178，本机隔离环境）

**任务**：TASK-178 `measure-like-write-path-capacity`（课题 5 首轮：点赞写路径容量边界与对账收敛测量）
**开工 HEAD**：`fac503b02536d819fd23d1dc46338a2757aec889`（`origin/main...main` = `0 1`）
**日期**：2026-10-07
**机器摘要**：`docs/perf/data/exp-like-write-path-capacity.json`
**原始证据（gitignore，未入库）**：`docs/perf/data/raw/task178-e1-*.json`、`task178-e2-*.json`、`task178-a1.json`、`task178-it-run-meta.json`、`task178-it-summary.json`、`task178-it-run.log`、`task178-channel.log`、`task178-g0-*.log`
**结论**：E1、E2、A1 三项**均归第一支（PASSED）**；`src/main` 零改动，未引入背压/告警/参数变更；未达外部门槛。

## 0. 结论摘要

1. **写路径的约束不在 DB 写或 Redis 往返，而在调度节拍与批次上限**。同包直调 `flushPendingLikes()`
   循环排空（绕开 5s `fixedDelay`）时，本机实测纯处理能力为 **4237–14845 队列元素/秒**（视形态而定），
   比 `200 批 / 5s 周期 = 40 ops/s` 的**算术上界**高两个数量级以上。因此 `40 ops/s` 是配置推导出的
   结构性上界，不是被测出来的服务率；两者在本报告分口径登记（§2）。
2. **对账成本由逐 record 的 Redis 往返主导，不是 DB 全表载入**。R3 档（20000 records / 100 万行）
   单轮对账 `reconcileWallMs = 35608.3`，其中 SET+DEL+SADD 写段 `34302.1`（**96.3%**），
   `selectRecordLikePairs` 载入 `1070.0`（**3.0%**）。墙钟耗时随 **record 数**近似线性
   （本机斜率约 1.7 ms/record），与行数只通过载入段弱相关。
3. **闭合断言在 9 个 E1 重复轮与 6 个 E2 重复轮上全部成立**：LRANGE 总量 = LTRIM 总量 = seed 元素数、
   每批 `LTRIM 条数 = LRANGE 条数`、行数增量 = INSERT 实际影响 − DELETE 实际影响、队列终态 `LLEN=0`、
   终态行集 = 末次动作净结果模型。
4. **收敛语义两条实测用例均通过**：A1.1 单轮纠偏（计数 999→3、成员集去掉漂移用户）；
   A1.2 两跳收敛（对账先用 DB 旧值覆盖、pending 保留 → flush 落库 → 再对账收敛到权威态）。
5. **A1.3（DEL+SADD 非原子窗口）按任务书只做代码级审计登记**，本轮未注入并发时序，属未覆盖项。

## 1. 环境与通道核验

| 项 | 实测 |
| --- | --- |
| 被测代码基线 | HEAD `fac503b0…`；`RecordLikeService.java` 及其配置零触碰（`git diff --name-only` 无生产源码） |
| scratch MySQL | 容器 `127.0.0.1:3307`，服务端 `8.0.46`；scratch 库 `task178_it` 由 `sql/02-record-db.sql` 机械改名 `record_db → task178_it` 重建，跑毕 `DROP DATABASE` |
| 演示库隔离 | 连接后强校验 `SELECT DATABASE() = task178_it`；不触碰演示 `record_db` |
| Redis 通道 | 宿主 `6379` 被本机原生 `redis-server 3.0.504` 占用（`task178-channel.log` 双探针留证），故沿 TASK-110/TASK-152 先例以临时 compose override 给**既有**容器 `sport-verify-redis` 追加宿主端口 `16379`；未新建容器、未停用户进程 |
| Redis 实例归属 | 两侧同一 `run_id = dcbb35cfd518529adfcb8a54b778ec2c10b3f9b0`：宿主侧 `16379` 探针与容器内 `redis-cli INFO server` 互证；`redis_version = 7.2.16`，容器内监听 `tcp_port = 6379` |
| Redis 隔离面 | 专用 `DB 12`；键前缀 `like:` / `lock:like:`；每形态/每档前后 `KEYS`+`DEL` 清理，跑毕再清一遍 |
| 锁语义同构 | 真实 Redisson 客户端（3.27.2）连真 Redis，`lock:like:flush` / `lock:like:reconcile` 走生产同款 `tryLock(3, -1, SECONDS)` + 看门狗，防重语义未降级 |
| JVM | `java 21.0.9`，`Windows 11 10.0`，surefire fork 默认最大堆 `3952 MB`（E2 R3 全表驻留未触及堆顶） |
| IT 直跑 | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，`IT_RUN_RC=0`，`BUILD SUCCESS`；缺环境变量时 assume 跳过（`Tests run: 0`，不计通过，`task178-it-skip.log`） |

## 2. 口径三分列（不得互相冒充）

| 口径 | 值 | 来源与限定 |
| --- | --- | --- |
| **算术上界** | 40 ops/s | `app.like.flush-batch=200` ÷ `@Scheduled(fixedDelay=5000)` 的推导值，描述生产调度**最多能消费多少**，与本机负载无关；**不是实测吞吐** |
| **实测纯处理能力** | 4237–7255（M1）/ 13661–14729（M2）/ 9616–14845（M3）队列元素/秒 | 本机隔离环境，IT 同包循环直调 `flushPendingLikes()` 至排空，**刻意绕开 5s 节拍**；测的是 flush 单次调用的排空能力，不含等待时间 |
| **有效服务率** | ≤ 40 ops/s（单实例） | 由节拍与批次上限封顶：`min(算术上界, 实测纯处理能力) = 算术上界`。本机实测远高于上界 ⇒ 上界主导；持续到达率超过 40 ops/s 时 `LLEN` 会无上界增长，与 flush 内部快慢无关 |

派生（算术推导，非实测）：按 40 ops/s 上界，5000 条积压需 25 个 tick ≈ 125 s 排空；本机 flush 纯处理只需 0.69–1.18 s。
两者差异说明**排空时延由节拍决定，不由 DB/Redis 决定**。此结论仅在本机隔离环境成立，不为生产规模背书。

## 3. E1 flush 服务率：三形态 × 3 轮

元素均按生产 JSON 形态（`recordId` / `userId` / `action` / `enqueuedAt`）经 RPUSH 注入 `like:pending:ops`；
每形态每轮先 `TRUNCATE record_like` + 清 Redis 键，再重灌。`batchCount` 均等于 `ceil(seeded/200)`，
`rounds` 均等于 `batchCount`（每轮恰好消费一批，无锁跳过轮）。

| 形态 | seed 元素 | 批次 | LRANGE 总量 | LTRIM 总量 | 去重后 likes/unlikes | INSERT 影响 / DELETE 影响 | 行数增量 | 终态 LLEN | 排空墙钟 ms（3 轮） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 全新对（5000 互异全 LIKE） | 5000 | 25 | 5000 | 5000 | 5000 / 0 | 5000 / 0 | 5000 | 0 | 1180.09 / 784.49 / 689.17 |
| M2 抵消混合（2500 对 like→unlike + 2500 互异 LIKE） | 7500 | 38 | 7500 | 7500 | 2500 / 2500 | 2500 / 0 | 2500 | 0 | 549.02 / 509.19 / 512.82 |
| M3 同 key 重复（2000 条仅 100 互异 key） | 2000 | 10 | 2000 | 2000 | 50 / 50 | 50 / 0 | 50 | 0 | 164.04 / 134.72 / 207.98 |

关键语义读数（逐轮均成立）：

- **M2 的"无行可删幂等路径"**：2500 个 (record_id,user_id) 的删除尝试合计影响行数 **0**（这些键从未落库），
  末次动作去重把 like→unlike 时序对折为净 DELETE；`DELETE` 无行可删天然幂等，行数增量仍等于净插入 2500。
- **M3 的末次动作去重**：2000 个元素经 `LinkedHashMap` 同键取末次后只剩 **100** 个净操作
  （50 LIKE + 50 UNLIKE），去重比 20:1；每批 200 元素折 10 净操作。
- **闭合断言**：`trimTotal == rangeTotal == seeded`、逐批 `trimN == rangeN`、
  `rowsDelta == likesAffected − unlikesAffected`、`llenFinal == 0`、
  终态行集与"全序列末次动作"模型逐键相等（`finalRowsMatchModel=true`）。

事务与批耗时（同 JVM 内随预热下降，如实登记）：

| 形态 | 首批 tx span ms | 稳态批次均值 ms（除首批） | tx span 总量 ms（3 轮） | 非事务段 ms（排空−tx，rep3） |
| --- | --- | --- | --- | --- |
| M1 | 194.65 / 23.80 / 15.77 | 27.31 / 23.12 / 20.44 | 850.09 / 578.66 / 506.31 | 182.86 |
| M2 | 7.26 / 5.46 / 4.13 | 8.94 / 8.55 / 8.04 | 338.14 / 321.68 / 301.55 | 211.28 |
| M3 | 19.17 / 12.71 / 16.77 | 8.69 / 7.85 / 13.02 | 97.36 / 83.36 / 133.98 | 73.99 |

首轮首批 `194.65 ms` 是同一 JVM 冷启动段（后续同形态批次稳态在 20–30 ms），不作为服务率代表值；
非事务段为 LRANGE/LTRIM 往返、JSON 解析、去重与 Redisson 加解锁开销之和。

轮间稳定性：结构性计数（`batchCount`/`rangeTotal`/`trimTotal`/`likesInput`/`unlikesInput`/
`likesAffected`/`unlikesAffected`/`rowsDelta`）在 3 轮间逐位相等（IT 内已作相等断言）；
耗时项存在预热型波动，按原值登记不取极值美化。

## 4. E2 对账成本曲线：三档 × 2 轮

单因素 = record 数，固定每 record 50 赞（R1 2 万行 / R2 20 万行 / R3 100 万行）。
`round1` 为 Redis 空态首轮，`round2` 为已收敛同值的稳态轮（用于验证对账无增量跳过）。
结构性计数两轮逐项一致：`pairsLoaded == rows`、`distinctRecords == records`、
`setCount == deleteCount == saddCount == records`、`saddMembersTotal == rows`。

| 档 | records / rows | 载入 ms（r1/r2） | SET ms（r1/r2） | DEL ms（r1/r2） | SADD ms（r1/r2） | Redis 写段合计 ms（r1/r2） | 对账总墙钟 ms（r1/r2） | 写段占比 | seed ms | 堆 MB（r1 前→后 / r2 前→后） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 400 / 20000 | 108.4 / 22.7 | 257.7 / 210.5 | 256.2 / 210.1 | 276.1 / 233.4 | 790.0 / 654.0 | 942.3 / 693.2 | 83.8% / 94.3% | 436.8 | 57.3→41.6 / 42.1→83.3 |
| R2 | 4000 / 200000 | 191.2 / 164.2 | 2099.0 / 1998.2 | 2056.3 / 1959.2 | 2215.6 / 2089.3 | 6371.0 / 6046.7 | 6668.7 / 6264.7 | 95.5% / 96.5% | 2237.0 | 56.2→180.8 / 180.8→225.3 |
| R3 | 20000 / 1000000 | 1070.0 / 877.3 | 11286.5 / 10592.4 | 11147.9 / 10512.4 | 11867.8 / 11110.8 | 34302.1 / 32215.6 | 35608.3 / 33295.7 | 96.3% / 96.8% | 11013.8 | 73.1→531.3 / 531.3→607.3 |

曲线与主导项判定（本机口径）：

- **随 record 数线性**：对账总墙钟 `0.94 → 6.67 → 35.61 s`（records `400 → 4000 → 20000`），
  每 record 均摊 `2.36 → 1.67 → 1.78 ms`；SET/DEL/SADD 三段各自也随 records 线性，
  单次 Redis 往返均摊 `0.50–0.66 ms`（六轮实测区间）。
- **非随行数线性**：载入段在行数 ×50（2 万→100 万）时只从 `108.4` 涨到 `1070.0 ms`（≈ ×9.9），
  而对账总墙钟涨 ≈ ×37.8 ⇒ **总成本由逐 record 的三次 Redis 往返（SET、DEL、SADD）主导**；
  R3 的 60000 次往返合计 34.3 s，折合每次约 `0.57 ms`。
- **TASK-137 消掉的 N+1 未在写段复现**：载入是单次 `selectRecordLikePairs` 全表批量（100 万行一次取回），
  但重建成员集的 Redis 写段仍是 per-record 三次往返，即 137 只优化了读侧 N+1，写侧往返次数仍是 `3 × records`。
- **全表驻留的真实成本**：R3 首轮堆 `73.1 → 531.3 MB`（+458.2 MB / 100 万行），
  稳态轮 `531.3 → 607.3 MB`；本机最大堆 3952 MB 未触及，但驻留量随行数增长，规模外推需自行验证。
- **稳态轮不省成本**：`round2` 的 SET/DEL/SADD 次数与成员总数与 `round1` 逐位相等
  （`setCount == deleteCount == saddCount == records`、`saddMembersTotal == rows`），
  即便 Redis 已是同值仍整轮重写；耗时 `35608.3 → 33295.7 ms` 仅小幅差异。

外推边界声明：以上曲线仅为**本机、scratch 库、专用 Redis DB、单实例、未达外部门槛**的证据。
不得据此断言生产规模的对账耗时，也不得据此得出任何线上延迟或吞吐改善结论。

## 5. A1 收敛语义审计

### 5.1 A1.1 pending 丢失漂移 → 单轮收敛（实测）

构造：`record_like` 行 `{1,2,3}`（record 990001），Redis 计数键 `like:count:990001 = "999"`、
成员集 `like:record:990001:users = {1,2,3,777}`（777 为落库缺失的漂移用户）。
执行一次 `reconcileLikeCounts()` 后实测：计数 `"3"`、成员集 `{1,2,3}`、`SISMEMBER 777 = false`。
**判定：单轮收敛成立**（DB 行为权威源覆盖 Redis 计数并重建成员集）。

### 5.2 A1.2 对账覆盖未落库 pending → 两跳收敛（实测）

构造：DB 行 `{1}`（record 990002），Redis 计数 `"2"`、成员集 `{1,2}`，队列内有一条**未 flush** 的
`LIKE(990002,2)`。逐跳实测：

| 跳 | 动作 | 计数 | 成员集 | LLEN | DB 行数 |
| --- | --- | --- | --- | --- | --- |
| 0 | 初始（Redis 领先 DB） | 2 | {1,2} | 1 | 1 |
| 1 | 对账（DB 旧值权威） | **1**（暂时回退） | {1} | **1**（pending 未被触碰） | 1 |
| 2 | flush 落库 | — | — | **0** | 2 |
| 3 | 再对账 | **2** | {1,2} | 0 | 2 |

**判定：两跳收敛成立**，与实现注释声明一致：pending 尚未落库的操作（窗口内）会被 DB 权威值覆盖，
下一轮 flush + 再下一轮对账后收敛；对账不动 pending 队列，`LLEN` 在第 1 跳保持 1、第 2 跳归 0。

### 5.3 A1.3 DEL + SADD 非原子窗口（代码级审计登记，未注入并发）

实现原文（`RecordLikeService.reconcileLikeCounts()`，本轮零改动）：

- 逐 record 重建顺序为 `opsForValue().set(countKey, size)` → `delete(usersKey)` → 非空时
  `opsForSet().add(usersKey, members)`，即 **DEL 与 SADD 是两次独立往返，未包 MULTI/Lua**；
- 注释自陈权衡：「已知权衡：pending 尚未落库的操作（窗口内）会被 DB 权威值覆盖，下一轮 flush +
  再下一轮对账后收敛——这正是「最终一致」的定义，写路径从不等待落库」，以及
  「重建成员集（DEL + SADD），恢复「重复点赞幂等」防线；空成员只删不 SADD」。

审计登记（按代码路径推理，未做并发时序实测）：

1. **窗口存在**：`delete(usersKey)` 与 `add(usersKey, dbMembers)` 之间，并发 `like()` 的 `SADD` 可落入窗口。
2. **合并行为**：`SADD` 是集合语义的并集写——若并发成员落在 DEL 之后、重建 SADD 之后，则该成员保留；
   若落在 DEL 之前，则会被 DEL 抹去，而其 `INCR` 计数与 pending 条目不受影响，于是出现
   "计数领先成员集" 的短暂不一致。
3. **幂等闸门可被翻转**：成员被 DEL 抹去的用户若再次点赞，`SADD` 返回 1 → 再次 `INCR` 并再推一条 pending；
   该重复 pending 由"同键末次动作去重 + `INSERT IGNORE` 联合主键冲突跳过"吸收，不产生重复行，
   计数键随后由下一轮对账按 DB 行数覆盖。
4. **收敛路径**：依赖「下轮 flush + 再下轮对账」，即 §5.2 实测的两跳机制；
   若该用户的 pending 推送本身失败（`pushPending` 记日志不阻断），则该成员只由对账按 DB 行为准抹平。
5. **未覆盖**：本轮**未注入并发时序**（多线程交错、跨实例锁竞争），上述 1–4 为代码级推理登记，
   不得当作已实测证明。

## 6. 三支判定逐项归属（任务书 §2.4 预注册）

| 课题 | 判据 | 实测 | 归属 |
| --- | --- | --- | --- |
| E1 flush 服务率 | 三形态 × 3 轮闭合断言全成立 | 9/9 轮 13 项 checks 全 true；`IT_RUN_RC=0` | **第一支（PASSED）** |
| E2 对账成本曲线 | 三档 × 2 轮结构性计数全成立、曲线登记 | 6/6 轮 7 项 checks 全 true；单因素=records 线性、写段主导 | **第一支（PASSED）** |
| A1 收敛语义 | A1.1/A1.2 收敛用例通过、A1.3 登记 | A1.1 与 A1.2 `checkPassed=true`；A1.3 代码级登记 | **第一支（PASSED）**（A1.3 并发时序属未覆盖，不影响 A1 判据） |

三支均无需 FAILED 支（未观察到闭合断言失败或收敛反例），也无需 UNDETERMINED 支
（MySQL 与受控 Redis 两路均可达，读数全部实测取得，无伪造、无重试刷绿）。

## 7. 偏差登记

1. **attempt-1 的三个红是 IT 判别式误设，不是被测语义异常**。首轮直跑 `IT_RUN_RC=1`，
   三条失败全部落在同一项 `roundsEqualsBatchesPlusOne`（预期"排空轮 = 批次 + 1"）。
   原始读数显示三形态均 `rounds == batchCount`（25/25、38/38、10/10）且其余 12 项 checks 全 true：
   排空循环在每次 flush 后立即复查 `LLEN`，故队列在"消费掉最后一批"的那一轮即归零并 break，
   不存在额外的空跑轮——是我写的判别式算术多算了 1。修正为 `roundsEqualBatchCount`
   （更强：若某轮被锁静默跳过，则 `rounds > batchCount` 仍会判红）后复跑，`IT_RUN_RC=0`。
   首轮证据保留未抹：`task178-it-run.attempt1.log`、`task178-e1-*-rep1.attempt1-oracle-defect.json`。
2. **宿主 6379 端口竞争**：原生 `redis-server 3.0.504` 占 6379，宿主侧任何 6379 连接都命不中受控容器
   （双探针 `3.0.504` vs `7.2.16` 留证）。按先例以临时 compose override 给既有容器追加 16379，
   未停用户进程、未新建容器。跑毕应把容器还原为基线 compose 端口映射（见 §9 还原记录）。
3. **预检探针退出码**：首轮 Redis 探针 `PROBE16379_RC=124`（`cat <&3` 读到套接字超时，
   身份信息已在超时前取到），属探针写法问题；补 `QUIT` 后次轮 `PROBE16379_RC=0`。不影响测量。
4. **harness `--it` 分支不能承载本 IT**：`scripts/verify/mvn-verify.sh --it` 的模块与类清单硬编码为
   leaderboard-service，故沿 TASK-177 Notice 先例以镜像 offline 纪律的完整命令直跑并留证（§9）。

## 8. 未覆盖项与不得推出的结论

1. **无线上/生产测量**：全部读数来自本机、scratch 库、专用 Redis DB、单实例、无并发访问的隔离环境；
   不得外推为线上延迟、并发吞吐或容量背书，不计算任何百分比"改善"值。
2. **未注入并发时序**：A1.3 的 DEL+SADD 窗口仅为代码级推理登记；多实例锁竞争、并发 `like()` 与对账交错、
   跨表删除可见性均未覆盖。
3. **未测热路径端到端**：`like()` / `unlike()` / `getLike()` 的单次调用延迟未在本轮测量
   （本轮只测 flush 排空与对账整轮），不得据此推断点赞接口 P99。
4. **未测积压增长形态**：只测排空能力与上界关系，未在 5s 节拍下做真实到达率压测与 `LLEN` 斜率测量；
   §2 的排空时延为算术推导。
5. **未测失败路径**：`INSERT IGNORE` 与 `DELETE` 同事务回滚、trim 不前置于 commit 的反例，
   本轮未构造故障注入；既有单测/IT 覆盖情况不在本任务复验范围。
6. **Redis 服务端版本面单一**：仅 7.2.16 容器实例；未覆盖其他版本/集群/哨兵拓扑。
7. **E2 规模上限止于 100 万行**：更大规模的全表驻留与对账耗时未测；曲线外推仅作本机参考。
8. **外部门槛未达**：`--mode=online` 与 CI 未跑（本次不 push，push 需用户单次显式授权）。
9. **不得推出的结论**：不得称"写路径容量已足够/不足"、不得据此调整 `flush-batch` 或对账周期、
   不得引入背压/告警/pipeline 化等改动——若后续要动，另立提案。

## 9. 证据文件与命令留证

IT 直跑命令（完整命令 + 退出码入 `task178-it-run.log`；官方门禁仍只经 `scripts/verify/mvn-verify.sh`）：

```bash
TASK178_IT_DB_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
TASK178_IT_DB_USER=root TASK178_IT_DB_PASSWORD=root \
TASK178_IT_REDIS_HOST=127.0.0.1 TASK178_IT_REDIS_PORT=16379 \
mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test \
  -Dtest=RecordLikeWritePathCapacityIT -Dsurefire.failIfNoSpecifiedTests=false
```

| 证据 | 内容 |
| --- | --- |
| `task178-it-run.log` | 次轮直跑全文：预检（容器表、`redis_version:7.2.16` + `run_id:dcbb35cf…`、`PORT_3307_OPEN`）、环境变量、完整命令、`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`、`IT_RUN_RC=0` |
| `task178-it-run.attempt1.log` | 首轮全文：`IT_RUN_RC=1`、3 条 `roundsEqualsBatchesPlusOne` 失败及逐条读数（偏差登记 §7.1 凭据） |
| `task178-it-skip.log` | 缺环境变量：`Running …RecordLikeWritePathCapacityIT` → `Tests run: 0`，`SKIP_RUN_RC=0`（跳过不记通过） |
| `task178-it-run-meta.json` | 实例归属与口径：`mysqlVersion=8.0.46`、`redisRunId`/`redisVersion=7.2.16`/`redisTcpPort=6379`、`redisDb=12`、`maxHeapMb=3952`、`flushBatch=200`、`javaVersion=21.0.9` |
| `task178-e1-{m1,m2,m3}-rep{1,2,3}.json` | E1 逐轮读数 + 逐批明细（`rangeN`/`trimN`/`likesInput`/`likesAffected`/`unlikesInput`/`unlikesAffected`/`txSpanMs`）+ `expected` + `checks` |
| `task178-e2-{r1,r2,r3}-round{1,2}.json` | E2 逐轮读数（载入/SET/DEL/SADD 分段耗时、成员总数、墙钟、堆三段、抽样三点）+ `checks` |
| `task178-a1.json` | A1.1/A1.2 实测读数与 `checkPassed`；A1.3 登记 `kind=CODE_AUDIT_REGISTERED`（未注入并发） |
| `task178-it-summary.json` | 本次 IT 汇总（e1 9 轮 / e2 6 轮 / a1） |
| `task178-g0-baseline.log` | 开工基线：offline `36/41/33/127/144/59/10` = 450、static 814、token 29 项逐位、契约门 rc=0 |
| `task178-g0-wordgate.log` | 词面门四形态 `ZERO_HIT rc=1` + 正向探针 `HIT rc=0` |
| `task178-channel.log` | 16379 通道建立与双侧身份互证 |

通道还原：测量跑毕以基线 compose 文件重建 redis 容器映射（`docker compose -f docker-compose.yml up -d redis`），
临时 override 文件随 `*.tmp` 一并删除；scratch 库由 `@AfterAll` 执行 `DROP DATABASE IF EXISTS task178_it`，
Redis DB12 的 `like:*` / `lock:like:*` 键同轮清理。
