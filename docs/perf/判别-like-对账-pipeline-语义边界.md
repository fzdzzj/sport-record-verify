# 判别：点赞对账 pipeline 化的语义边界（TASK-179，课题 5 第二轮）

> 状态：**已收口**。结论＝**GO**（六判据 (a)–(e) 未发现劣化，(f) 仅登记绝对数字）。
> **GO 不构成生产改动许可**：`RecordLikeService.reconcileLikeCounts` 与全部 `src/main` 本轮零触碰，pipeline 化实施须另立提案。
> 一切读数限定「本机、隔离 scratch 环境、未达外部门槛」；服务率对比只登记绝对毫秒与往返次数，不写改善百分比、不外推生产收益。

## 0. 一句话结论

在真 Redis + scratch MySQL 上，以同一套探针对照「逐 record 三次往返」基线与「executePipelined 按批提交」候选：
**命令内容与次序逐条相同（600/600，无 MULTI/EXEC 包装），最终收敛态两方案都逐 record 等于 DB 权威，
锁语义与 pending 行为不变，中断残留均可由下轮对账收敛**；差异集中在三处可观测形态——
`DEL→SADD` 服务端空窗由每次约 0.975ms（本机中位）压到约 0.006ms（本机中位）但**没有归零**、
关键时点外部可见态由「成员集为空」变为「旧成员集仍在（未提交）」、
批内中断的提交边界由连接层收尾决定而非业务可控。
两支变异（抽掉 DEL、DEL/SADD 次序颠倒）均被 (a) 判据捕获（对照 200 收敛 / 变异 0 收敛），判别力非空转。

## 1. 范围与红线遵守

| 红线 | 落实方式 |
| --- | --- |
| 只判别不优化、零生产改动 | 候选是本 IT 内的 test-only 切片 `PipelineReconcileCandidate`；`git diff --name-only 0b5175b -- '*/src/main/*'` 命中 0 |
| 唯一 mvn 入口 | 门禁全部经 `bash scripts/verify/mvn-verify.sh`；IT 直跑沿 TASK-177/178 Notice 例外（§12），完整命令与 rc 入本文件 |
| 观测不到的维度登记未覆盖 | §14 逐条列明；未以「命令序列数学等价」替代任何一项对照 |
| 服务率只登记绝对数字 | §9 表内为毫秒与次数，无百分比、无外推 |
| 环境隔离 | scratch 库 `task179_it`（跑后 `DROP DATABASE`）、Redis 专用 DB 13、演示 `record_db` 与 DB0 键零触碰 |
| 禁并发 mvn | 全流程串行；离线基线 450 与静态门 814 读数见 §13 |

## 2. 环境与装配

| 项 | 读数 |
| --- | --- |
| scratch MySQL | 容器 `127.0.0.1:3307`，服务端 `8.0.46`；库 `task179_it` 由 `sql/02-record-db.sql` 机械改名重建 |
| Redis 通道 | 宿主 `6379` 被本机原生 `redis-server.exe`（PID 6480）抢占（`g0-env2.log`：`+PONG` 后 `INFO` 连接被重置；`netstat` 显示 6379 双监听），故沿 TASK-178 先例以临时 compose override 给**既有**容器 `sport-verify-redis` 追加宿主端口 `16379`，未新建容器、未停用户进程 |
| Redis 实例归属 | 宿主 `16379` 探针与容器内 `redis-cli INFO server` 同为 `run_id 3c7000f34fe52b6fc67c8d4754b09b4ad1a1ebbb`、`redis_version 7.2.16`、容器内 `tcp_port 6379`；IT 侧 `task179-run-meta.json` 的 `redisRunId` 同值（测量内自证） |
| Redis 键空间 | 专用 DB 13，键前缀 `like:` / `lock:like:`，每轮跑前后 `KEYS`+`DEL` 清理 |
| 数据规模 | J1/J2 语义档 200 records × 50 赞 = 10 000 行（启用 MONITOR，故取小档）；(f) 服务率档 2 000 records × 50 = 100 000 行（不启用 MONITOR）。任务书 §2.1 建议的 2 000 records 规模用于服务率档，语义档缩小理由＝MONITOR 命令流体积与逐命令扰动，已在此登记 |
| 前置漂移态 | 语义档每 record 预置「DB 的 50 个成员 + 1 个 DB 没有的幽灵成员 + 计数 999」，使 (a) 判据真正考验 `DEL` 的重建语义（无此前置态则抽掉 DEL 也可收敛，判别式空转） |
| 连接工厂 | `org.redisson.spring.data.connection.RedissonConnectionFactory`（`task179-run-meta.json`） |

**与任务书 §1.2 前提不符（已按事实修正判别装配，登记于 §15）**：任务书写「生产 Redis 客户端 StringRedisTemplate（Lettuce 底层）」。
本仓普查结果：`record-service/pom.xml` 引 `redisson-spring-boot-starter`，该 starter 的 POM 对 `spring-boot-starter-data-redis` **显式排除 `io.lettuce:lettuce-core` 与 jedis**；本地仓库内不存在 lettuce-core 构件；
Spring Boot 自动配置序使 `RedissonAutoConfigurationV2` 先注册 `RedisConnectionFactory`，故生产 `StringRedisTemplate` 走 Redisson 的 spring-data 连接。
候选必须与生产同装配，因此 `executePipelined` 在 Redisson 连接工厂上实现；任务书 §2.2 中「Lettuce pipeline 上限」口径对本仓不适用，分批 500/1000 作为预注册参数保留并都做了实测。

## 3. 观测手段与扰动口径

同一套探针复用于基线与候选，维度不减：

1. **连接内逐命令计时**：`StringRedisTemplate` 子类 + JDK 动态代理包裹 `opsForValue/opsForSet/delete`，记录每条命令的起止纳秒。基线口径＝「DEL 往返返回 → SADD 往返发出」的客户端间隔；候选无逐命令往返，该栏改记「批次内 DEL 排队 → SADD 排队」间隔，并在读数里显式标注语义（不得混读）。
2. **独立只读连接**：第二个 Redisson 客户端 + 裸 `StringRedisTemplate`，做两类观测——① 关键时点主动探测（基线在该 record 的 DEL 返回后、SADD 发出前采样 8 条 record；候选在同等程序位置即该 record 的 DEL 排队后、SADD 排队前采样）；② 轮询采样（同一组 8 条 record 反复读 `SCARD`）。轮询为采样观测：**未命中不等于窗口不存在**，基线档 65 轮 / 520 次观测、末轮周期 15ms，命中空窗 2 次、旧态 338 次；候选档 6 轮 / 48 次观测、命中空窗 0 次。误差上界即轮询周期，按原值登记。
3. **服务端 `MONITOR`**：独立裸 socket 连接发 `MONITOR`，采集服务端逐命令时间戳与客户端地址——空窗分布与命令序比对的权威凭据。`MONITOR` 会给每条命令追加一次服务端写出，因此**语义档耗时不可当服务率证据**；服务率档（§9）全程不启用 `MONITOR`。
4. 计时按原值登记，不取极值、不重试刷绿；`IT` 复跑读数落 `docs/perf/data/raw/task179/<run>/`，历次缺陷轮另存子目录（§15）。

## 4. J1 基线语义快照（逐 record 三次往返）

200 records / 10 000 行、幽灵漂移前置态、MONITOR 开启。SET/DEL/SADD 各 200 次、SADD 成员合计 10 000；单轮对账墙钟 **1049.131ms**（含 MONITOR 扰动）。

| 口径 | 样本数 | min | p50 | p90 | max | 合计 |
| --- | --- | --- | --- | --- | --- | --- |
| 服务端 `DEL→SADD` 空窗（µs 时间戳差，ms 表示） | 200 | 0.651 | 0.975 | 1.542 | 21.669 | 271.075 |
| 客户端 `DEL→SADD` 往返间隔 | 200 | 0.0391 | 0.0661 | 0.1113 | 16.4676 | 52.6431 |

- **中间可见态序列**：8 条采样 record 的关键时点探测**全部读到 `SCARD=0`**（成员集在该瞬间确实为空），即 A1.3 由「代码级注释声明」升级为**外部连接实测可见**的空窗；轮询另在 520 次观测中独立命中 2 次。
- **命令流**：`MONITOR` 捕获 like 写命令 600 条（=3×200），未见 `MULTI/EXEC`；观测到 49 个不同客户端地址（含探针与轮询自身连接）。
- **锁防重**：对账持锁期间第二把 `tryLock(3, -1, SECONDS)` 等待 **3011.793ms** 后被拒（`secondTryLockAcquired=false`），且采样时刻 `lock:like:reconcile` 键存在 ⇒ 写发生在锁内。
- 结构闭合检查 5 项全 true（计数三元组、成员总数、服务端空窗样本数、探测数=采样数、逐 record 收敛等于 DB 权威）。

## 5. J2 候选对照与差异清单

候选与基线同数据、同装配、同分组结果、同每 record 的 `SET/DEL/SADD` 三元命令，仅提交方式改为按批 `executePipelined`。分批语义为**每批命令数上限**（一条 record 的三元命令不跨批拆分；`SADD` 的多成员计 1 条）。

| 维度 | 基线 | 候选 cap500 | 候选 cap1000 |
| --- | --- | --- | --- |
| 墙钟（200 records，MONITOR 开启） | 1049.131ms | 85.893ms | 59.654ms |
| 提交次数（批次） | 600 次逐条往返 | 2（501 + 99 条命令） | 1（600 条命令） |
| 服务端 `DEL→SADD` 空窗 p50 / max / 合计 | 0.975 / 21.669 / 271.075ms | 0.006 / 0.053 / 1.398ms | 0.006 / 0.070 / 1.573ms |
| 客户端栏语义 | 往返间隔 | 批内排队间隔（p50 0.0086ms） | 批内排队间隔（p50 0.0035ms） |
| 关键时点外部可见态（8 条采样） | 8/8 读到成员集为空 | 0 空窗、8/8 读到旧成员集（51） | 0 空窗、8/8 读到旧成员集（51） |
| 轮询空窗命中 | 2 / 520 次观测 | 0 / 48 | 0 / 32 |
| 命令序 | — | 与基线逐条相同（600/600），无包装命令 | 同 |

逐条差异登记（任务书 §2.2 要求「差异逐条登记，不得只报最终态」）：

1. **单 record 可见时点**：基线每 record 处理瞬间即对外可见；候选把可见性推迟到该 record 所属批次提交时刻——批次内每条 record 的外部可见时点被**批量对齐**（cap1000 时本档 200 条 record 一次可见）。这不是延迟改善而是**可见性形态改变**：读侧在提交前看到的是全批旧值。
2. **空窗形态**：服务端空窗仍存在但由「一次往返时长」量级（本机 p50 0.975ms）压到「批内连续命令间隔」量级（本机 p50 0.006ms），**未归零**；因此并发写被覆盖的时序面缩小但不消失。基线的空窗是网络往返决定的，候选的空窗是服务端逐命令处理决定的，两者不同源，不可按「数学上等价于 0」处理。
3. **中间态形态互换**：基线的外部可见中间态是「成员集为空」，候选是「旧成员集（含幽灵成员）仍在、计数仍为旧值」。判据 (d) 的口径是「锁外可见的中间态**不多于**基线」——候选空窗探测 0 次 ≤ 基线 8 次，但候选新增了「未提交旧态」这一形态，已如实登记为形态差异而非数量优势。
4. **失败原子性**：见 §8——基线失败单元是单条命令，候选的提交边界在连接层收尾处，批内中断可留下与该批相关的部分应用态。
5. **连接占用**：候选在批内持续占用同一连接（`RedissonConnectionFactory` 借出连接直至批收尾），基线每次往返各自借用/归还；本机观测到候选档的命令来自更少客户端地址（30/29 个 vs 基线 49 个，含探针连接）。连接被长时间占用对连接池的影响**未在本轮测量**（§14）。
6. **命令序可重放**：`MONITOR` 逐条比对，两方案命令内容与顺序完全一致，差异仅在提交分组（相邻命令的服务端间隔分布：基线 p50 ≈ 1ms，候选批内 p50 ≈ 0.006ms、批间为提交点）。

## 6. J3 六判据逐项裁决（预注册口径）

| 判据 | 基线观测 | 候选是否劣化 | 裁决依据（一手读数） |
| --- | --- | --- | --- |
| (a) 最终收敛态 | 逐 record 等于 DB 权威 | 否，两批大小均逐 record 等于 DB 权威（幽灵成员被 `DEL` 清除） | `finalConvergenceEqualsDbAuthority=true`×3 轮；违反项列表为空 |
| (b) 并发写行为 | 存在覆盖窗口 | 否，未新增丢失面 | 完成态后注入两形态均 10/10 存活；「该 record 已处理完」类别丢失数候选=基线=0；pending 未丢弃（95/95、17/17、19/19 逐一对齐） |
| (c) 失败模式 | 下轮对账收敛 | 否，两种候选中断形态均下轮 200/200 收敛，且中止后锁已释放 | §8 残留普查 |
| (d) 锁语义 | 第二把 tryLock 被拒、写在锁内 | 否，候选同锁同参数（被拒耗时 3005.895ms）；空窗探测 0 ≤ 基线 8 | §4、§7 探测读数；新增旧态形态已登记 |
| (e) 命令序可重放 | — | 否，600/600 逐条相同、无 MULTI/EXEC、分组顺序相同 | §5 第 6 条 |
| (f) 服务率量化 | 见 §9 | 仅登记绝对数字，不作门槛 | §9 |

## 7. 并发 like 注入（时序分类）

注入走**生产 `like()` 序列**（`SADD` 幂等闸门 → `INCR`+`PERSIST` → `RPUSH` pending），由独立连接在对账进行中执行。

确定时点（目标 record = 700007，DB 50 行）：

| 注入时点 | `like()` 返回计数 | 对账后 | 注入成员是否存活 |
| --- | --- | --- | --- |
| 基线 · 该 record `SET` 之前 | 1（计数键当时不存在） | 计数 50 / 成员 50 | **否**（被 `DEL`+重建覆盖） |
| 基线 · `DEL` 与 `SADD` 之间 | 51 | 计数 51 / 成员 51 | 是，但留下与 DB 权威的漂移（计数 51、成员 51） |
| 基线 · `SADD` 之后 | 51 | 计数 51 / 成员 51 | 是（同样漂移，待下轮对账抹平） |
| 候选 · 批内 `DEL` 排队后、`SADD` 排队前 | 1 | 计数 50 / 成员 50 | **否**（整批尚未提交，随后被批内重建覆盖） |
| 候选 · 批次提交之后 | 51 | 计数 51 / 成员 51 | 是（同基线的漂移形态） |

批量并发（对账进行中持续注入，按该 record 的重建完成时刻分类）：

| 形态 | 接受注入数 | 早于该 record 被触碰（丢失/尝试） | 落在 DEL→SADD 窗口 | 已处理完（丢失/尝试） | 完成后再注入存活 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 96 | 25 / 26 | 0 / 0 | 0 / 70 | 10 / 10 |
| 候选 cap500 | 17 | 15 / 16 | 0 / 0 | 0 / 1 | 10 / 10 |
| 候选 cap1000 | 19 | 18 / 19 | 0 / 0 | 0 / 0 | 10 / 10 |

- 「早于被触碰」一类在两形态都会丢失——这是既有的「pending 未落库操作被 DB 权威覆盖」语义（TASK-178 A1.2），**不是 pipeline 新增丢失面**；把它与窗口类混算会得出「候选丢失率更高」的伪结论，故本轮按类别对照（登记于 §15）。
- 「落在 `DEL→SADD` 窗口」两类尝试数均为 0：注入自身的往返耗时（本机数毫秒）大于窗口宽度（本机亚毫秒），采样线程撞不进这个窗口。因此**窗口内行为只能由关键时点探测（§5 第 3 条）与确定时点注入证明，不能由统计注入证明**，已如实标注。
- 候选的丢失面比基线多一类**时长**：某 record 在其所属批次提交前一直是旧态，注入落在此期间的都会被批内重建覆盖（表中第一列）。这是可见性推迟的同一枚硬币，登记为形态差异。

## 8. 失败模式与残留态（每轮跑后再做一次正常对账）

| 注入 | 调用方所见错误 | 残留普查（converged / setOnly / halfApplied / untouched） | 下轮对账 | 中止后锁 |
| --- | --- | --- | --- | --- |
| 基线：第 3 条 record 的 `DEL` 后抛错 | `IllegalStateException: task179 injected failure after 3 records` | 2 / 0 / 1 / 197 | 200 / 0 / 0 / 0 收敛 | 已释放 |
| 候选：第 1 个批次提交后抛错 | `IllegalStateException: … between batches after 1 committed batches` | 167 / 0 / 0 / 33 | 200 / 0 / 0 / 0 收敛 | 已释放 |
| 候选：批内排队第 1 条命令后抛错 | `IllegalStateException: … while queueing batch (1 commands queued, batch not committed)` | 0 / **1** / 0 / 199 | 200 / 0 / 0 / 0 收敛 | 已释放 |

- 基线的失败单元是**单条命令**（可见 1 条 record 处于「`SET`+`DEL` 已完成、`SADD` 未到」的半应用态）。
- 候选按批提交时残留呈**整批粒度**（167 条已完成、33 条未触碰，无半应用 record）。
- 关键发现：批内排队中断（业务侧异常）仍有一条 `SET` 生效 ⇒ Spring Data 的 `executePipelined` 在回调抛错时仍会走到连接层收尾，**已排队命令是否触达服务端由连接层收尾决定，不由业务代码决定**。因此「批次要么全不提交要么全提交」不成立，实施提案若要以批为原子单位须另设手段（本轮不实施）。
- 三种残留都不产生不可恢复态：下轮对账均收敛到 DB 权威 ⇒ (c) 不劣化。

## 9. (f) 服务率量化（绝对数字，不写百分比）

2 000 records × 50 赞 = 100 000 行，逐 record 与两档批大小交替重复测量 3 轮，无 MONITOR。

| 形态 | 每轮命令数 | 每轮往返/提交次数 | 三轮墙钟 ms | 三轮合计 ms |
| --- | --- | --- | --- | --- |
| 基线（逐条往返） | 6 000 | 6 000 | 2998.041 / 2832.446 / 2919.595 | 8750.082 |
| 候选 cap500 | 6 000 | 12（合计 36） | 272.408 / 240.220 / 242.977 | 755.605 |
| 候选 cap1000 | 6 000 | 6（合计 18） | 202.952 / 162.381 / 195.108 | 560.441 |

- 命令总量两方案**逐位相等（6 000）**，差异只在提交分组；三轮收敛普查均 2 000/2 000。
- 本机绝对耗时由 8750.082ms 量级降至 755.605ms / 560.441ms 量级（合计口径，3 轮）。**不换算百分比、不外推生产**：本机为单实例空库隔离环境、无并发读、`record_like` 无二级索引前提与线上不同。
- cap1000 与 cap500 的本机差值（560.441 vs 755.605ms，合计口径）不足以支撑选参结论——两者都远小于基线，且本档命令总量仅 6 000；分批上限的真实约束（服务端输出缓冲、单连接占用时长、失败粒度）须由实施提案另行判别。

## 10. 变异红（判别力证明）

前置态同上（幽灵成员 + 错计数），对照与两支变异各跑一遍并逐 record 普查：

| 轮 | converged | setOnly | halfApplied | untouched | 备注 |
| --- | --- | --- | --- | --- | --- |
| 对照（生产对账） | 200 | 0 | 0 | 0 | 判别式前提成立 |
| 变异 1：候选抽掉 `DEL` | 0 | 200 | 0 | 0 | `deleteCommandCount=0`、`set.add` 仍 200 ⇒ 幽灵成员未被清除，(a) 判据判红 |
| 变异 2：候选把 `DEL` 放到 `SADD` 之后 | 0 | 0 | 200 | 0 | `tripleOrderPreserved=false` ⇒ 重建成员随即被 `DEL` 抹掉，(a) 判据判红 |

两支变异均被同一探针捕获 ⇒ §4/§5 的空窗与收敛读数不是空转指标。变异只在 test-only 候选切片内注入，未改被测服务。

## 11. 三支判定与归属

- **J1**：基线语义快照无论 J3 结果如何均为有效产出——`DEL→SADD` 空窗从 TASK-178 A1.3 的代码级登记升级为**实测分布**（服务端 p50 0.975ms / max 21.669ms / 合计 271.075ms）+ 外部可见性证明（8/8 关键时点探测 + 2 次轮询命中）。
- **J2**：候选对照未发现 (a)–(e) 劣化；差异清单 §5 六条 + §7 + §8 逐条登记。
- **J3**：**GO**（六判据全过，(f) 仅登记）。按任务书 §2.4，GO 后**pipeline 化实施另立提案**，本任务不改生产。
- 环境可达 ⇒ **无 UNDETERMINED 分支**；未使用规程外手段，无伪造读数、无重试刷绿。

## 12. 复现步骤（完整命令 + 退出码）

IT 直跑（harness `--it` 硬编码 leaderboard 清单，无法承载 record-service IT ⇒ 沿 TASK-177/178 Notice 例外，命令镜像 harness offline 纪律）：

```
TASK179_IT_DB_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
TASK179_IT_DB_USER=root TASK179_IT_DB_PASSWORD=root \
TASK179_IT_REDIS_HOST=127.0.0.1 TASK179_IT_REDIS_PORT=16379 \
TASK179_IT_RUN=run2-j1j2-measurement \
mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test \
  -Dtest=RecordLikeReconcilePipelineJudgeIT -Dsurefire.failIfNoSpecifiedTests=false
```

实测：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，`IT_RUN_RC=0`，用时 60.70s（全文 `docs/perf/data/raw/task179/run2-j1j2-measurement/it-run.log`）。
缺任一变则 assume 跳过：不导出 `TASK179_IT_*` 复跑同一命令得 `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、rc=**0**，**按未覆盖记账，不记通过**（`run4-closure-evidence/it-closure-run.log` 第二段）。
收口复跑（`TASK179_IT_RUN=run4-closure-evidence`，独立 run 目录，不回写测量轮读数）：`Tests run: 9`，rc=**0**；与测量轮的结构性计数**逐位相同**——SET/DEL/SADD 各 200、成员总数 10 000、`MONITOR` like 写命令 600、批次数 2/1、命令序 `identical=true`、变异红对照 200 vs 变异各 0、三支归属 GO；仅耗时浮动（基线档墙钟 1049.131 → 957.775ms；服务率基线三轮 2998.041 / 2832.446 / 2919.595 → 3479.996 / 3664.577 / 3421.904ms）。报告与 JSON 的数字取**测量轮原值**。

## 13. 门禁实测表

| 门 | 命令 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工核验 | `git rev-parse HEAD` / `git rev-list --left-right --count origin/main...main` / `git status --porcelain` | `0b5175bf405cb5d70eeb9b17f44bd21affeb465a`、`0 1`、0 行 | 过 |
| G1 离线基线 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**；分模块 `36/41/33/127/144/59/10`、合计 **450**、Failures/Errors/Skipped 全 0、BUILD SUCCESS | 过 |
| G2 静态门基线 | `bash scripts/verify/mvn-verify.sh --static=record-service` | rc=**1**、`You have 814 Checkstyle violations`（严格 814 持平，不增） | 过 |
| G3 IT 直跑 | §12 命令 | rc=**0**、9/9 全绿 | 过 |
| G4 词面门 | 正则自 `ci.yml` 现场提取（58 字节），四形态 `git grep --untracked -n -I -iE` + 三态判定 | 四形态全 **ZERO_HIT rc=1**；正向探针四形态全 **HIT rc=0**，探针文件已删（`PROBE_GONE=yes`）；新 IT 文件单独复扫 0 命中 | 过 |
| G5 契约门（在途） | `bash scripts/verify/mailbox-contract.sh --open TASK-179 --baseline=0b5175b…` | rc=**0**（判据 A 含 1 个待办进行中 + 判据 B 清单一致） | 过 |
| G6 空白门 | `git diff --check` / `git diff --cached --check` | 均 rc=**0** | 过 |
| G9 token | PLAN.md 逐 token `grep -cF`（29 项） | 开工 29/29 均＝任务书 §5 基线 +1（TASK-178 C-02/C-03 已追加所致），`TOKEN_VIOLATIONS=0` | 过 |
| G10 tasks.json | `python -c "import json;json.load(...)"` | rc=**0** | 过 |

## 14. 未覆盖项与不得推出的结论

1. **服务端批内连接硬中断未覆盖**：候选档试图用 `CLIENT ID` / `CLIENT LIST` 定位批处理所在物理连接后 `CLIENT KILL ID`，Redisson 的 spring-data 连接对 `CLIENT` 命令返回 `UnsupportedOperationException` ⇒ 该维度登记未覆盖（未以「批次命令数学上一起写出」替代观测）。已覆盖的是**业务侧异常时**的连接层收尾行为（§8 第三行）。
2. **客户端库前提只测本仓装配**：仓库解析结果无 lettuce-core，结论仅适用于 Redisson 连接工厂下的 `executePipelined`；不得外推为「任意 Redis 客户端 pipeline 语义相同」。
3. **连接池占用与并发读未测**：§5 第 5 条只登记了观测到的客户端地址数量差异，未测候选长时间占用借出连接对池的压力、以及生产节拍（10min 一次）下的并发读放大。
4. **未测生产端到端**：`like()/unlike()/getLike()` 的接口延迟、`@Scheduled` 节拍下的实际服务率、真实到达率积压形态均不在本轮 ⇒ 不得推断点赞接口 P99 或对账周期占用。
5. **规模与实例边界**：语义档 200 records、服务率档 2 000 records（10 万行）；Redis 为单机 7.2.16，未覆盖集群/哨兵/不同版本；`MONITOR` 开启档的耗时不作服务率证据。
6. **窗口内并发覆盖无法用统计注入证明**（§7 第二条）：仅由关键时点探测与确定时点注入支撑。
7. **未达外部门槛**：`--mode=online` 与 CI 未跑，未 push（push 须用户显式单次授权）。
8. **不得推出的结论**：不得据本轮修改 `reconcileLikeCounts`、调整分批参数、引入 pipeline 化或增删锁；GO 只授权**另立实施提案**（任务书 §0.1/§2.4）。本轮未发现需要 NO-GO 登记的劣化，也未发现基线本身的未知语义异常。

## 15. 偏差与勘误登记

1. **任务书 §1.2「Lettuce 底层」与本仓装配不符**（§2 已述）：候选改在 `RedissonConnectionFactory` 上实现 `executePipelined`，属「与生产同装配」的必要修正，非判别设计漂移；§2.2 的 Lettuce 分批上限口径对本仓不适用，500/1000 两档参数照测。
2. **语义档规模由 2 000 records 缩为 200 records**：MONITOR 逐命令扰动与命令流体积决定语义档取小档；任务书建议的 2 000 records 规模保留给 (f) 服务率档。
3. **(b) 判据口径细化**：初版按「聚合丢失率」对照，出现候选 0.941 / 基线 0.221 的**测量面伪结论**（候选整轮更快，注入绝大多数落在「尚未触碰该 record」类别，而该类别在两形态都会覆盖）。改为按注入相对该 record 重建完成时刻的**时序分类**对照后，判据回到「候选不得新增丢失面」的原意。首轮聚合读数与错因均未抹除（`attempt3-baggregate-defect/`）。
4. **候选客户端间隔读数曾为负值**：候选把批内排队时刻与批提交时刻混用，得 p50 −30.7ms 的无意义值；已改记「批内排队间隔」并显式标注栏语义，缺陷轮读数保留在 `attempt4-clientgap-artifact/`。
5. **IT 首两轮为测量面缺陷**：`sql/02-record-db.sql` 建的 scratch 表缺 05 号迁移的 `archived` 列，生产 `like()` 的 `selectById` 列清单不匹配 ⇒ `BadSqlGrammarException`，9 项全红（`attempt1-sqlsyntax-defect/`）；scratch 建表后补该列即恢复。此为测量工具与仓库既有迁移脚本的一致性缺陷，**非被测语义异常**，不改 `src/main`、不改读数口径。另有一处 `sport_record` 列表反引号笔误（同目录留证）。
6. **(d) 候选暂停钩子曾挂错位置**：初版把候选的暂停挂在模板的 `chunk.commit` 事件上，而候选走 `RedisConnection` 不经模板方法 ⇒ 暂停未生效、第二把 tryLock 在 4.185ms 即成功（假读数，`attempt2-partial-readings/`）；改挂到候选自身的批次提交后探针位后复测为 3005.895ms 被拒。
7. **(c) 「第 2 个批次后中断」在 200 records/cap500 档等于跑完全程**（残留为 0 的虚判据），改为「第 1 个批次后中断」以真正考察批粒度残留（`attempt5-failuremode-trivial/`）。
8. 词面门 pattern 现场提取、`git grep` 的 `--untracked` 前置、Redis 探针补 `QUIT`、Bash 入口仅 `D:\git\Git\bin\bash.exe` 执行仓库根脚本文件——均沿用 PLAN.md 已登记口径与 TASK-178 先例。
9. **跳过证明轮的收尾缺陷（已修，原文留档）**：C-01 落库后收口复跑时，`Tests run: 9` 全绿，但缺变量证明轮报 `Tests run: 1, Errors: 1`——`@BeforeAll` 正确 assume 跳过后 `@AfterAll` 仍尝试落 `task179-it-summary.json`，`rawDir` 为空即抛错。该路径正是 §2.5「缺变量不视为通过」的门禁凭据，故补 `@AfterAll` 守卫（环境门未过直接返回），复跑为 `Tests run: 0` rc=0。**测量逻辑与判据口径零改动**，缺陷轮读数留档 `run4-closure-evidence/attempt-skippath-defect/`。
