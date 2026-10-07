# 任务书：TASK-178 measure-like-write-path-capacity（课题 5 首轮：点赞写路径容量边界与对账收敛测量）

派发：指导 Agent（2026-10-07）。本任务书与提案三件套由指导侧亲笔；执行侧负责测量实施与台账闭环（C-01 证据笔 + C-02 台账笔），不得改动派发笔内容。

## 0. 硬约束与红线

1. **纯测量 + 语义审计，零生产改动**：`src/main` 全部零触碰（重点：`RecordLikeService.java`、`RecordLikeMapper`、`app.like.flush-batch`、两个 `@Scheduled` 周期、锁与幂等语义）；不引入背压/告警/配置变更；测量若揭示容量或语义缺陷 ⇒ 如实登记，修复另立提案，本任务不顺手改。
2. **唯一 mvn 入口**：一切官方门禁只经 `bash scripts/verify/mvn-verify.sh`。唯一例外：本任务 IT（`--it` 分支硬编码 leaderboard 清单无法承载，沿 TASK-177 Notice 先例）以镜像 harness offline 纪律的完整命令直跑并留证（完整命令 + 退出码入报告）。
3. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时脚本落仓库根 `*.tmp`（已 gitignore），用毕删除；脚本内 `exec > file 2>&1` 落盘输出（PowerShell `2>&1` 会与 bash stdout 冲突）。
4. **git 纪律**：禁 push / PR / `add -A` / `add .` / stash；逐路径显式 add；提交信息经 `-F` 文件落盘。
5. **禁并发 mvn**；离线测试基线 450 只增不减（IT 不被收集）。
6. **环境纪律**：scratch 隔离（scratch 库 + 受控 Redis，键带任务前缀或专用 DB/索引，绝不触碰演示 `record_db` 与用户数据卷）；既有容器沿「仅无进程/端口冲突且为既有容器才可启动」规程；宿主 6379 被占 ⇒ 沿 TASK-152 先例处理或如实走 UNDETERMINED 分支，禁伪造读数、禁重试刷绿。
7. **措辞纪律**：一切耗时/吞吐数字限定「本机、隔离环境、未达外部门槛」；「算术上界（batch/周期）」与「实测纯处理能力」分口径登记，不得以算术值冒充实测吞吐（机会总览 P2 原文要求）；不写线上延迟改善或生产容量背书；新增文档零禁词、零词面门正则字面量。
8. **token 与 PLAN**：受保护 29 项 token 只增不减；PLAN.md 纯追加。
9. **停止条件**：任何「顺手优化」（背压、批次/周期调整、对账增量化、Redis pipeline 化）⇒ 停手回报登记候选提案；测量轮内发现语义异常（如闭合断言失败）⇒ 按三支判定 FAILED 支如实收口，不掩盖。

## 1. 背景与问题定义

### 1.1 史实链

- 机会总览 P2「点赞 pending 列表处理能力与全量对账」：验证门槛原文 = 测到达率、LLEN 斜率、批次有效净操作数、flush 事务耗时、对账行数/耗时/堆峰值；先判断是队列容量、DB 写、Redis 往返还是全表驻留主导，再仅改一个因素；不能把固定延迟/批次上限的算术值当真实吞吐。
- TASK-137 消除了对账读成员 N+1（`selectRecordLikePairs` 一次全表载入），但登记「全表读取及 Redis 重建仍可能随数据量变重；没有点赞压测或吞吐收益数字」。
- TASK-174（课题 2）治理读路径防击穿（F17 互斥重建）与观测（`like.pending.queue.size` / `like.pending.head.age.ms` 双 Gauge + 1000 阈值 warn）；写路径容量与对账成本仍零实测。
- 本任务为课题 5（点赞写路径）首轮：测量 + 审计，结论驱动后续（沿课题 1 relay 链「先测后改」模式）。

### 1.2 代码事实（指导侧 2026-10-07 亲验，HEAD `24eda89`）

- 写路径热段：SADD 幂等闸门 → INCR/DECR → `pushPending` RPUSH JSON（失败仅记日志不阻断）；`flushPendingLikes` 每 5s、`LRANGE 0..flushBatch-1`（默认 200）、同 key 末次动作去重、事务内 `batchInsertIgnore` + `batchDelete` 同进退、**事务成功后才 LTRIM**；Redisson 锁 `lock:like:flush` 多实例防重。
- 对账 `reconcileLikeCounts` 每 10min：`selectRecordLikePairs()` 全表载入 → 内存按 record 分组 → 逐 record SET 计数 + DEL+SADD 重建成员集（DB 行为权威源）；注释声明两类窗口语义（pending 未落库被覆盖的两跳收敛、DEL+SADD 非原子窗口）。
- `record_like` 联合主键 (record_id, user_id)，无二级索引需求（全表扫描即对账语义）。
- 40 ops/s = 200 批 / 5s 周期的**算术上界**，不是实测服务率。

## 2. 测量设计（指导侧预注册，只可细化不得漂移）

### 2.1 E1 flush 服务率（三形态，隔离 scratch 真库 + 受控真实 Redis）

- 队列元素沿生产 JSON 格式（recordId/userId/action/enqueuedAt）；seed 经 RPUSH；每形态独立清队列与 scratch 表。
- M1 全新对：5000 个互异 (record_id, user_id) 全 LIKE（INSERT 主导路径）。
- M2 抵消混合：2500 对 like→unlike 时序对（净 DELETE，无行可删幂等路径）+ 2500 对互异纯 LIKE。
- M3 同 key 重复：2000 条仅 100 个互异 key 的重复操作（末次动作去重路径）。
- 驱动：test-only IT 同包直调包私有 `flushPendingLikes()` 循环至排空（绕过 5s fixedDelay，测纯处理能力；生产有效服务率受调度上界约束，报告分口径登记）。
- 逐轮记录：LRANGE 条数、去重后净操作数（likes/unlikes 分列）、事务批写耗时、LTRIM 条数、轮总耗时。
- **闭合断言（任一失败 ⇒ FAILED）**：LTRIM 总量 = 处理总条数；scratch `record_like` 行数增量 = 净插入 − 净删除；队列终态 LLEN=0；每形态重复 3 轮（清空重 seed）读数稳定性登记。

### 2.2 E2 对账成本曲线（单因素 = record 数，固定平均每 record 50 赞）

- 三档：R1 = 400 records × 50 = 2 万行；R2 = 4000 × 50 = 20 万行；R3 = 20000 × 50 = 100 万行。
- seed 沿建表脚本灌 scratch 库（批量 INSERT IGNORE）；每档执行对账路径（直调 `reconcileLikeCounts()` 或经同一切片装配），记录：`selectRecordLikePairs` 载入耗时、分组后 map 规模、逐 record Redis 写（SET/DEL/SADD）总耗时、对账总耗时。
- 每档重复 2 轮（第二轮 Redis 已收敛为同值，测稳态）；曲线 + 外推边界声明（本机证据不为生产规模背书）登记入报告与 JSON。

### 2.3 A1 收敛语义审计

- A1.1 pending 丢失漂移→单轮收敛（可测）：构造 Redis 计数/成员集含用户 X 而 DB 无行（模拟 pushPending 失败后落库缺失）→ 执行对账 → 断言计数被 DB 值覆盖、成员集不含 X。
- A1.2 对账覆盖未落库 pending→两跳收敛（可测）：seed pending（未 flush）+ DB 旧行 → 对账（计数被 DB 旧值覆盖，Redis 暂时回退）→ flush 落库 → 再对账 → 断言计数/成员集收敛到含新行的权威态。
- A1.3 DEL+SADD 非原子窗口（登记）：按代码级审计登记窗口存在性、并发 SADD 的合并行为与「下轮 flush + 对账」收敛路径，引用实现注释原文；不注入并发时序（超出本轮范围，登记为未覆盖）。

### 2.4 三支判定（预注册）

- **PASSED**：E1 三形态 × 3 轮与 E2 三档 × 2 轮全部闭合断言通过，A1.1/A1.2 收敛用例通过 ⇒ 登记容量边界（纯处理能力、40 ops/s 算术上界、有效服务率三者分列）与对账耗时曲线。
- **FAILED**：任一闭合断言失败或 A1.1/A1.2 出现收敛反例 ⇒ 如实登记（含复现步骤与原始读数），修复候选另立提案。
- **UNDETERMINED**：环境不可用（MySQL/Redis 任一不可达且规程内手段用尽）⇒ 如实收口。
- E1/E2/A1 可分别归属不同支（如 E1 PASSED 而 A1 FAILED），逐项登记，不合并裁决。

### 2.5 实施面

- IT 新文件（沿 TASK-148/156「受限切片 + 真实组件」先例，test-only）：建议单文件 `record-service/src/test/java/com/sportverify/record/service/RecordLikeWritePathCapacityIT.java`（同包直调包私有方法），环境变量 `TASK178_IT_DB_URL / TASK178_IT_DB_USER / TASK178_IT_DB_PASSWORD / TASK178_IT_REDIS_HOST / TASK178_IT_REDIS_PORT`（沿 `TASK<NNN>_IT_*` 惯例），缺任一变量 assume 跳过不视为通过；Redisson 可用测试专用最小装配或以受控直连替代，装配方式由执行侧定但须与生产锁语义同构（tryLock 防重语义不降级）。
- 报告：`docs/perf/测量-like-写路径-容量与对账.md`；机器摘要：`docs/perf/data/exp-like-write-path-capacity.json`；原始数据 `docs/perf/data/raw/`（ignored）。

## 3. 开工读数（时序差惯例沿 TASK-176/177 §3）

- 任务书落盘时点 HEAD = `24eda89ba5beb75ba34b3591657c7d61cc46750d`（origin/main = 本地，`0 0`）；派发笔入库后基线前移（`0 1`）。执行侧开工核验：HEAD = 派发笔哈希、领先 `0 1`、工作树零残留。
- 离线测试基线：450（36/41/33/127/144/59/10 分模块）；Checkstyle `--static=record-service` 基线 814（新文件零违规 ⇒ 不增）。

## 4. 白名单（只改清单判据）

- **派发笔（指导侧，已入库，执行侧零触碰）**：`spec/changes/measure-like-write-path-capacity/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。
- **C-01 证据笔**：IT 新文件 + `docs/perf/测量-like-写路径-容量与对账.md` + `docs/perf/data/exp-like-write-path-capacity.json`。
- **C-02 台账笔**：`tasks.json` 闭环勾选 + 本任务书收口记录纯追加 + `work/mailbox/tasks/TASK-178/handoff.md`（新）+ `work/mailbox/PLAN.md` 纯追加（验收记录表；token 追踪表如需第 30 行落表按先例处理）。
- **禁触**：`src/main/**`、既有迁移脚本、其他 spec/changes 目录、`.codex/`、`.trae/`。

## 5. 受保护 tokens 基线（29 项，只增不减；2026-10-07 指导侧实测 `grep -cF work/mailbox/PLAN.md`）

- `13.4` = 18
- `18.0` = 20
- `73.93` = 19
- `68.8` = 19
- `6315` = 16
- `1.8612` = 15
- `3.3066` = 15
- `5.7056` = 15
- `9.408` = 15
- `36525962432` = 15
- `36586847965` = 14
- `36438897772` = 15
- `36399582548` = 14
- `36098038547` = 14
- `2806` = 21
- `598` = 14
- `36736221648` = 13
- `36808102571` = 8
- `36821040708` = 6
- `36845152965` = 5
- `36871294588` = 5
- `36880083885` = 6
- `36958994260` = 6
- `36976873215` = 6
- `36992632143` = 5
- `36995450125` = 3
- `37008317295` = 4
- `37021305016` = 5
- `37591580687` = 4

收口时 PLAN.md 所有 token 出现次数必须 ≥ 基线值。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前全项亲跑并记录退出码）**：`tasks.json` 语法（python json.load）rc=0；词面门四形态（default / `LC_ALL=C` / `LC_ALL=zh_CN.UTF-8` / `LC_ALL=C.UTF-8`，正则自 `.github/workflows/ci.yml` 现场提取，三 pathspec 排除同口径）全 ZERO_HIT rc=1 + 正向探针 rc=0 + 三态判定；`git diff --check`（含 staged）rc=0；契约门在途 `bash scripts/verify/mailbox-contract.sh --open TASK-178 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF、末尾换行完整。
2. **收口门禁（C-02 后亲跑留证入 handoff）**：offline 七模块 450 恒等分模块逐位；`--static=record-service` 不增；IT 直跑全绿 rc=0（完整命令留证）；契约门无参 rc=0；只改清单与 handoff 声明逐条全等；PLAN.md 自派发笔起纯追加。
3. **提交结构**：C-01 证据笔（IT + 报告 + JSON；subject 沿仓库 test/docs 笔先例，禁性能改善措辞）；C-02 台账笔 `docs(mailbox): 登记 TASK-178 验收记录与测量闭环（TASK-178）`。
4. **handoff.md**（沿 TASK-176/177 同构 §1-§10）：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 测量证据（E1 三形态、E2 三档、A1 三点关键读数与三支归属）/ 逐门实测表 / 受保护 token 前后读数 / 未覆盖项与不得推出的结论 / 提交表。
