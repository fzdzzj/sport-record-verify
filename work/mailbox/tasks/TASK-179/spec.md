# 任务书：TASK-179 judge-like-reconcile-pipeline-semantics（课题 5 第二轮：对账 pipeline 化语义判别）

派发：指导 Agent（2026-10-07）。本任务书与提案三件套由指导侧亲笔；执行侧负责判别实施与台账闭环（C-01 证据笔 + C-02 台账笔），不得改动派发笔内容。

## 0. 硬约束与红线

1. **只判别、不优化、零生产改动**：`RecordLikeService.reconcileLikeCounts` 及全部 `src/main` 冻结；pipeline 候选只存在于 test-only 切片；GO 结论也不得直接改生产（另立提案）。
2. **唯一 mvn 入口**：官方门禁只经 `bash scripts/verify/mvn-verify.sh`；本任务 IT 沿 TASK-177/178 直跑例外（`--it` 硬编码 leaderboard 清单 Notice 延续），完整命令 + rc 入报告。
3. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时脚本 `*.tmp` 用毕删；脚本内 `exec > file 2>&1` 落盘输出。
4. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件。
5. **禁并发 mvn**；离线基线 450 只增不减；`--static=record-service` 基线 814 不增。
6. **环境纪律**：scratch 隔离（绝不触碰演示 `record_db` 与用户数据卷；Redis 键空间隔离）；宿主 6379 竞争沿 TASK-178 临时 16379 override 先例（用毕必须删除并还原基线映射）；不可达 ⇒ UNDETERMINED 支如实登记。
7. **判别纪律（沿 TASK-153 先例）**：观测不到的维度登记「未覆盖」，不得以「数学上等价」跳过对照；变异红必须证明判别力；**消费端/读路径幂等不构成 pipeline 化授权**。
8. **措辞纪律**：一切读数限定「本机、隔离环境、未达外部门槛」；服务率对比只登记绝对数字，不写改善百分比、不外推生产收益；新增文档零禁词、零词面门正则字面量。
9. **token 与 PLAN**：受保护 29 项只增不减（基线 = TASK-178 任务书 §5 值；C-02 追加会使部分 +1，收口实测 ≥ 基线即可）；PLAN.md 纯追加。
10. **停止条件**：判别中发现 pipeline 使 (a)–(e) 任一劣化 ⇒ 直接 NO-GO 收口，不尝试修补候选方案（修补属实施提案范畴）；发现基线本身有未知语义异常 ⇒ 停手回报。

## 1. 背景与问题定义

### 1.1 史实链

- TASK-178 E2（课题 5 首轮，2026-10-07）：对账成本 96.3% 在逐 record 三次 Redis 往返（SET+DEL+SADD），R3 百万行单轮 35.6s、每 record 均摊 ≈1.7ms、每往返 ≈0.57ms；DB 全表载入仅 3.0%。结论：对账瓶颈是 Redis 往返次数，非 DB。
- TASK-153 先例（2026-09-27）：「批末统一标记 SENT」因四类语义差异被裁 NO-GO——本判别是其同族方法在点赞对账上的应用：先裁语义，再谈实施。
- A1.3（TASK-178）：DEL+SADD 非原子窗口已按代码级审计登记（未注入并发 ⇒ 未覆盖）——本任务 J1 把该窗口从「注释声明」升级为「实测快照」。

### 1.2 代码事实（指导侧 2026-10-07 亲验，HEAD `8788cd5`）

- 对账每 10min：`selectRecordLikePairs()` 全表载入 → 内存按 record 分组 → 逐 record：`SET count:recordId` + `DEL usersKey` + `SADD usersKey members...`（`RecordLikeService.java` L330-365）；Redisson 锁 `lock:like:reconcile` 多实例防重（tryLock 3s 等待，拿不到跳过）。
- 生产 Redis 客户端：`StringRedisTemplate`（Lettuce 底层）；仓库无 `executePipelined` 生产用法（仅 TASK-178 IT 用过 `RedisCallback`）。
- 每条命令各自一次往返 = 3×records 次往返（SADD 批量成员算一次）；pipeline 化后同连接批量提交，往返次数降为常数（分批 flush）。

## 2. 判别设计（指导侧预注册，只可细化不得漂移）

### 2.1 J1 基线语义快照（逐 record 基线，真 Redis + scratch DB）

- seed：沿 TASK-178 E2 形态缩样（建议 2000 records × 50 赞 = 10 万行，兼顾观测分辨率与时长；执行侧可微调但须登记理由）。
- 逐事件观测（观测点以独立只读连接轮询 + 关键时点主动探测实现，频度与误差登记）：
  - 计数键/成员集键的中间可见态序列（对账中读到旧值/新值的时序）；
  - DEL→SADD 空窗：成员集短暂为空的时长分布（逐 record 采样）；
  - 并发 like 注入（观测连接在对账进行中执行 SADD+INCR+RPUSH 生产序列）：该成员是否被对账覆盖丢失、计数是否漂移、pending 是否仍在队列；
  - 锁防重：第二把 tryLock 被拒读数。
- 输出：基线快照表（时序 + 分布 + 并发行为定性）。

### 2.2 J2 pipeline 候选对照（同数据同装配）

- 候选实现：test-only 切片以 `executePipelined`（或等价 `RedisCallback` 批量）重放与基线**等价命令序列**（分组结果相同、每 record 同样 SET+DEL+SADD 三命令，仅提交方式不同）；分批大小候选 500/1000（Lettuce pipeline 上限内），两批大小都测。
- 观测维度与 J1 完全一致（同一观测探针复用）；差异逐条登记：单 record 可见时点变化、空窗形态变化、pipeline 部分失败语义（注入一次连接中断，观测已提交/未提交边界）vs 基线逐条失败、连接占用（pipeline 期间阻塞连接）。
- **命令序可重放**：记录 pipeline 实际发出的命令序（Lettuce 集群/LRU 无关，单机即可），与基线命令序逐条比对。

### 2.3 J3 六判据（预注册，任一 (a)–(e) 劣化 ⇒ NO-GO）

- (a) 最终收敛态：对账完成后 Redis 计数/成员集与 DB 权威逐 record 相等（两方案都必须全等；pipeline 若最终态都错直接 NO-GO）。
- (b) 并发写行为：对账期间注入的并发 like，其「被覆盖丢失」的行为不劣于基线（基线本身会覆盖窗口内并发 SADD——候选不得新增丢失面）。
- (c) 失败模式：注入连接中断后，两方案的残留状态可恢复性对比（下轮对账能否收敛）；pipeline 不得产生基线没有的不可恢复态。
- (d) 锁语义：候选仍在对账锁保护内执行全部 Redis 写；锁外可见的中间态不多于基线。
- (e) 命令序可重放：pipeline 命令序与基线逐条等价（仅提交分组不同）。
- (f) 服务率量化：同数据下两方案对账总耗时对比（绝对数字登记，不写百分比、不外推）。
- 变异红：至少对「抽掉 DEL 命令」「打乱 record 顺序导致 SADD 覆盖」两个注入变异验证 J1/J2 观测探针能捕获（判别力证明）。

### 2.4 三支判定（预注册）

- **GO**：六判据全过 ⇒ 登记 GO + (f) 量化；pipeline 化实施另立提案（本任务不改生产）。
- **NO-GO**：任一 (a)–(e) 劣化 ⇒ 如实登记劣化证据（含复现步骤），本方向终止。
- **UNDETERMINED**：环境不可用 ⇒ 如实收口。
- J1/J2/J3 分别登记归属；J1 基线快照无论 J3 结果如何都是有效产出（升级 A1.3 的代码级登记为实测）。

### 2.5 实施面

- IT：`record-service/src/test/java/com/sportverify/record/service/RecordLikeReconcilePipelineJudgeIT.java`（沿 TASK-178 IT 同包同装配模式），环境变量 `TASK179_IT_DB_URL / TASK179_IT_DB_USER / TASK179_IT_DB_PASSWORD / TASK179_IT_REDIS_HOST / TASK179_IT_REDIS_PORT`，缺任一 assume 跳过不视为通过。
- 报告：`docs/perf/判别-like-对账-pipeline-语义边界.md`；机器摘要：`docs/perf/data/exp-like-reconcile-pipeline-semantics.json`；raw：`docs/perf/data/raw/`（**按 run 目录隔离**，沿 TASK-178 复核 Notice 采纳：`task179/<run>/` 结构，避免复跑覆盖）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `8788cd55385546aaf8296510e8566ad4385d1166`（origin/main = 本地，`0 0`）；派发笔入库后基线前移（`0 1`）。
- 离线测试基线：450（36/41/33/127/144/59/10）；Checkstyle `--static=record-service` 基线 814。

## 4. 白名单（只改清单判据）

- **派发笔（指导侧，已入库，执行侧零触碰）**：`spec/changes/judge-like-reconcile-pipeline-semantics/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。
- **C-01 证据笔**：IT 新文件 + 报告 + JSON。
- **C-02 台账笔**：`tasks.json` 闭环勾选 + 本任务书收口记录纯追加 + `work/mailbox/tasks/TASK-179/handoff.md`（新）+ `work/mailbox/PLAN.md` 纯追加。
- **禁触**：`src/main/**`、既有迁移脚本、其他 spec/changes 目录、`.codex/`、`.trae/`。

## 5. 受保护 tokens 基线（29 项，只增不减；2026-10-07 指导侧实测沿 TASK-178 任务书 §5，C-02 追加后部分 +1 为预期）

`13.4`=18、`18.0`=20、`73.93`=19、`68.8`=19、`6315`=16、`1.8612`=15、`3.3066`=15、`5.7056`=15、`9.408`=15、`36525962432`=15、`36586847965`=14、`36438897772`=15、`36399582548`=14、`36098038547`=14、`2806`=21、`598`=14、`36736221648`=13、`36808102571`=8、`36821040708`=6、`36845152965`=5、`36871294588`=5、`36880083885`=6、`36958994260`=6、`36976873215`=6、`36992632143`=5、`36995450125`=3、`37008317295`=4、`37021305016`=5、`37591580687`=4

（TASK-178 C-02/C-03 已追加台账，开工实测可能已高于上列基线——执行侧开工时以实测为准登记，收口只增不减对开工值核验。）

## 6. 门禁与提交结构

1. **预提交门禁（每笔前全项亲跑记录 rc）**：`tasks.json` 语法 rc=0；词面门四形态全 ZERO_HIT rc=1 + 正向探针 rc=0 + 三态判定；`git diff --check` rc=0；契约门在途 `--open TASK-179 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF、末尾换行完整。
2. **收口门禁（C-02 后亲跑留证入 handoff）**：offline 450 分模块逐位；`--static=record-service` 不增；IT 直跑全绿 rc=0；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **提交结构**：C-01 证据笔（IT + 报告 + JSON；禁性能改善措辞）；C-02 台账笔 `docs(mailbox): 登记 TASK-179 判别结论与台账闭环（TASK-179）`。
4. **handoff.md**（沿 TASK-178 同构）：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 判别证据（J1 快照、J2 差异、J3 六判据逐项、变异红）/ 逐门实测表 / token 前后读数 / 未覆盖项与不得推出的结论 / 提交表。

---

## 7. 收口记录（执行侧 2026-10-07 纯追加，不改写上文任何预注册口径）

- **三支归属**：J1 基线快照＝有效产出（A1.3 由代码级登记升级为实测分布）；J2 候选对照＝差异逐条登记；J3＝**GO**（(a)–(e) 未发现劣化，(f) 只登记绝对数字）。**GO 不改生产**，pipeline 化实施须另立提案（§0.1/§2.4）。环境两路可达 ⇒ 无 UNDETERMINED 分支。
- **J1 关键读数**（200 records × 50 赞 = 10 000 行，幽灵成员 + 错计数前置态，MONITOR 开启）：服务端 `DEL→SADD` 空窗 p50 0.975ms / p90 1.542ms / max 21.669ms / 合计 271.075ms；8 条采样 record 的关键时点探测**全部读到成员集为空**（外部独立连接实测可见）；轮询 520 次观测独立命中空窗 2 次；对账持锁期间第二把 `tryLock(3,-1)` 等待 3011.793ms 被拒。
- **J2 关键差异**：分批 500（2 个批次：501+99 条命令）与 1000（1 个批次 600 条命令）两档实测——服务端空窗压至 p50 0.006ms（max 0.053 / 0.070ms）但**未归零**；关键时点外部可见态由「成员集为空」换为「旧成员集仍在（批次未提交）」（8/8 读到 51）；命令序与基线**逐条相同**（600/600，无 `MULTI/EXEC`），分组顺序相同；服务率档（2 000 records / 100 000 行，无 MONITOR，交替 3 轮）基线三轮 2998.041 / 2832.446 / 2919.595ms（合计 8750.082ms，每轮 6000 条命令、6000 次往返），候选 cap500 为 272.408 / 240.220 / 242.977ms（合计 755.605ms，每轮 12 次提交）、cap1000 为 202.952 / 162.381 / 195.108ms（合计 560.441ms，每轮 6 次提交）——命令总量逐位相等，只登记绝对值。
- **并发写与失败模式**：按注入相对该 record 重建完成时刻的**时序分类**对照（聚合丢失率会把「尚未触碰」这一既有覆盖语义误读为候选劣化，登记为 §8 偏差 3）——「已处理完」类别两形态均 0 丢失，完成后再注入两形态均 10/10 存活，pending 未被任何形态丢弃；中断注入三形态残留均可由下轮对账收敛（基线残留含 1 条半应用 record；候选按批粒度残留；**批内排队遇业务异常时仍有一条 `SET` 触达服务端** ⇒ 提交边界由连接层收尾决定，「批次原子」不成立）。
- **变异红**：对照 200/200 收敛；抽掉 DEL ⇒ 0 收敛（`setOnly=200`）；DEL/SADD 次序颠倒 ⇒ 0 收敛（`halfApplied=200`）⇒ 探针具判别力。
- **门禁**：offline 七模块 `36/41/33/127/144/59/10` = **450 恒等**、rc=0；`--static=record-service` rc=1 且 **814 持平**；IT 直跑 `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` rc=0；词面门四形态 ZERO_HIT rc=1 + 探针四形态 HIT rc=0；`git diff --check` rc=0；tasks.json `json.load` rc=0；受保护 token 29 项只增不减。契约门**收口无参 rc=0**；C-02 在途判据 B 因交付物中文文件名超出该工具 ASCII 路径收割面而判红 1 条（工具表达边界，沿 TASK-178 §1.5 结论，不试图刷绿）。
- **偏差登记（详见 handoff §1/§15）**：① 任务书 §1.2「Lettuce 底层」与本仓装配不符——`redisson-spring-boot-starter` 的 POM 显式排除 `io.lettuce:lettuce-core`，生产 `StringRedisTemplate` 实为 `RedissonConnectionFactory`；候选按「与生产同装配」在该工厂上实现 `executePipelined`，§2.2 的 Lettuce 分批上限口径对本仓不适用，500/1000 两档照测。② 语义档规模由建议的 2000 records 缩为 200 records（MONITOR 逐命令扰动与命令流体积），2000 records 规模保留给 (f) 服务率档。③ 服务端批内**硬**中断（`CLIENT KILL ID`）在 Redisson spring-data 连接上不支持 `CLIENT` 命令 ⇒ 登记未覆盖。④ IT 前三轮为测量面缺陷（scratch 建表缺 05 号迁移 `archived` 列、候选暂停钩子挂错位置、客户端间隔栏混用提交时刻致负值、聚合丢失率伪结论），原始读数均留档未抹除。
- **未达外部门槛**：`--mode=online` 与 CI 未跑，未 push。全部读数限定本机隔离环境（scratch 库 `task179_it`、Redis DB 13、单实例），不为任何数字背书生产收益。
