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
