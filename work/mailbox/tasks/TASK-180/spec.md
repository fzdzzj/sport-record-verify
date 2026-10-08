# 任务书：TASK-180 impl-like-reconcile-pipeline（课题 5 第三轮：对账 pipeline 化实施）

派发：指导 Agent（2026-10-08）。本任务书与提案三件套由指导侧亲笔；执行侧负责实施与台账闭环（C-01 实施笔 + C-02 台账笔），不得改动派发笔内容。

## 0. 硬约束与红线

1. **实施面唯一**：生产改动限 `RecordLikeService.reconcileLikeCounts` 方法体（命令构造与分批提交分离）；锁、分组、日志语义、调度参数（10min/60s）、锁键、flush 路径、key 结构零改动；`PIPELINE_BATCH_SIZE` 默认 500，定档实测后可改默认值但须登记依据。
2. **语义门不可妥协**：(a)(d)(e) 在生产方法上 IT 直测复验；任一劣化 ⇒ FAILED 支回滚改造并如实登记，禁带病合入。
3. **唯一 mvn 入口**：官方门禁只经 `bash scripts/verify/mvn-verify.sh`；IT 沿 TASK-177/178/179 直跑例外（`--it` 硬编码 leaderboard 清单 Notice 延续），完整命令 + rc 入报告。
4. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时 `*.tmp` 用毕删；脚本内 `exec > file 2>&1` 落盘输出。
5. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件且无 BOM（Write 工具先例，禁 PowerShell `Set-Content`）。
6. **禁并发 mvn**；离线基线 450 只增不减；`--static=record-service` 基线 814 不增。
7. **环境纪律**：scratch 隔离（绝不触碰演示 `record_db` 与用户数据卷；Redis 键空间隔离 DB13）；宿主 6379 竞争沿临时 16379 override 先例（用毕删除并还原基线映射）；不可达 ⇒ UNDETERMINED 支。
8. **措辞纪律**：一切读数限定「本机、隔离环境、未达外部门槛」；耗时对比只登记绝对数字，不写改善百分比、不外推生产收益；新增文档零禁词、零词面门正则字面量。
9. **token 与 PLAN**：受保护 29 项只增不减（开工实测为基线，C-02 追加后 ≥ 开工值）；PLAN.md 纯追加。
10. **停止条件**：改造中发现生产装配与判别切片行为不一致（判别结论不可迁移）⇒ 停手回报，禁强行适配；发现基线未知语义异常 ⇒ 停手回报。

## 1. 背景与问题定义

### 1.1 史实链

- TASK-178 E2（2026-10-07）：对账成本 96.3% 在逐 record 三次往返，百万行单轮 35.6s、每 record ≈1.7ms、每往返 ≈0.57ms。
- TASK-179 判别（2026-10-08，GO）：六判据全过、变异红 9/9、命令序两档 `identical=true`、Redisson `RedissonConnectionFactory` 上 `executePipelined` 与基线等价；服务率量化 (f) 档留档（报告 `docs/perf/判别-like-对账-pipeline-语义边界.md` §6 与 `exp-like-reconcile-pipeline-semantics.json`）。
- 继承未覆盖项（TASK-179 复核 Notice）：连接占用维度、(b) 桶零判别力（并发写窗口内尝试数为 0，结论靠类别对照）、CLIENT KILL 服务端硬中断不支持——三条直入本任务验收清单的未覆盖项，验收判别式不得依赖。

### 1.2 生产代码事实（指导侧 2026-10-08 亲验，HEAD `b6086b6`）

- 现实现逐 record 三命令：`SET like:count:{id}`（L350）→ `DEL like:record:{id}:users`（L354）→ `SADD` 批量成员（L356，空成员只 DEL 防御分支）；Redisson 锁 `lock:like:reconcile`（tryLock 3s）；全表载入后 `LinkedHashMap` 分组保序。
- 客户端：`StringRedisTemplate`（Redisson `RedissonConnectionFactory`，无 Lettuce）；仓库无生产 pipeline 用法先例——本实施是首例。

## 2. 实施设计（指导侧预注册，只可细化不得漂移）

### 2.1 改造形态

- 循环体拆两段：构造段——遍历分组结果，逐 record 生成命令三元组（与现命令逐条等价，含空成员只 DEL 分支）；提交段——累积至 `PIPELINE_BATCH_SIZE` 即 `executePipelined` 提交，循环尾提交余量。
- 命令等价性由 IT (e) 门守卫（pipeline 实际命令序 vs 构造序逐条比对——沿判别 IT 的 MONITOR 命令流方法）。
- 日志语义不变：`纠正 N 条记录` 计数含全部 record（含空成员）；异常语义不变（提交失败即本轮对账失败，锁 finally 释放）。

### 2.2 定档实测

- 同负载两档（500/1000）预试：耗时绝对数字 + 内存观测登记，按判别轮口径定档写入 `PIPELINE_BATCH_SIZE` 并在报告登记依据；两档差异在噪声内 ⇒ 取 500（保守默认）。

### 2.3 语义门（IT 直测生产方法）

- (a) 收敛态：对账完成后逐 record 断言 Redis 计数/成员集 == DB 权威（全量断言，非抽样）。
- (d) 锁内执行：IT 期间第二锁尝试被拒（沿判别 IT 探针）；改造后代码路径静态断言（pipeline 提交调用点在锁 try 块内）。
- (e) 命令序：MONITOR 命令流与构造序逐条比对（等价三元组、顺序一致、无多余命令）。
- (b)(c)：沿判别轮证据边界登记「未在生产路径重验」——判别切片与生产装配一致性由「IT 复用判别轮装配直测生产方法」结构性保证，不再单独注入。
- 变异红（改造后代码上重验判别力）：IT 内注入式抽掉 DEL / 乱序 SADD 两变异须被 (a)/(e) 捕获。

### 2.4 三支判定（预注册）

- **PASSED**：(a)(d)(e) 复验全绿 + 变异红 9/9 形态 + offline 450 不减 + 静态 814 不增 + 前后耗时绝对数字落档。
- **FAILED**：任一语义门劣化 ⇒ 回滚改造（生产代码还原 HEAD 形态）、如实登记劣化证据与复现步骤，提案转 NO-GO 归档。
- **UNDETERMINED**：环境不可用 ⇒ 如实收口（改造代码不入库或还原，登记阻塞证据）。

### 2.5 实施面与产物

- 生产：`RecordLikeService.java` 一处方法体（+ `PIPELINE_BATCH_SIZE` 常量）。
- IT：`record-service/src/test/java/com/sportverify/record/service/RecordLikeReconcilePipelineIT.java`；环境变量 `TASK180_IT_DB_URL / TASK180_IT_DB_USER / TASK180_IT_DB_PASSWORD / TASK180_IT_REDIS_HOST / TASK180_IT_REDIS_PORT`，缺任一 assume 跳过不视为通过。
- 报告：`docs/perf/实施-like-对账-pipeline-化.md`；机器摘要：`docs/perf/data/exp-like-reconcile-pipeline-impl.json`；raw：`docs/perf/data/raw/task180/<run>/`（run 目录隔离，沿 TASK-178 Notice 采纳）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `b6086b6e83a13e893090eb48638b615d96ab81d8`（origin/main = 本地，`0 0`）；派发笔入库后基线前移（`0 1`）。
- 离线测试基线：450（36/41/33/127/144/59/10）；Checkstyle `--static=record-service` 基线 814。

## 4. 白名单（只改清单判据）

- **派发笔（指导侧，已入库，执行侧零触碰）**：`spec/changes/impl-like-reconcile-pipeline/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。
- **C-01 实施笔**：`RecordLikeService.java` + IT 新文件 + 报告 + JSON。
- **C-02 台账笔**：`tasks.json` 闭环勾选 + 本任务书收口记录纯追加 + `work/mailbox/tasks/TASK-180/handoff.md`（新）+ `work/mailbox/PLAN.md` 纯追加。
- **禁触**：除 `RecordLikeService.java` 外的 `src/main/**`、既有迁移脚本、其他 spec/changes 目录、`.codex/`、`.trae/`。

## 5. 受保护 tokens 基线（29 项，只增不减；开工实测为基线登记，收口 ≥ 开工值）

`13.4`、`18.0`、`73.93`、`68.8`、`6315`、`1.8612`、`3.3066`、`5.7056`、`9.408`、`36525962432`、`36586847965`、`36438897772`、`36399582548`、`36098038547`、`2806`、`598`、`36736221648`、`36808102571`、`36821040708`、`36845152965`、`36871294588`、`36880083885`、`36958994260`、`36976873215`、`36992632143`、`36995450125`、`37008317295`、`37021305016`、`37591580687`（出现次数以开工 `grep -cF` 实测为准逐项登记，TASK-179 C-02 追加后部分已 +1~+2）。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前全项亲跑记录 rc）**：`tasks.json` 语法 rc=0；词面门四形态全 ZERO_HIT rc=1 + 正向探针 rc=0 + 三态判定；`git diff --check` rc=0；契约门在途 `--open TASK-180 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF、末尾换行完整。
2. **收口门禁（C-02 后亲跑留证入 handoff）**：offline 450 分模块逐位；`--static=record-service` 不增；IT 直跑全绿 rc=0；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **提交结构**：C-01 实施笔 `feat(record): 对账 Redis 往返 pipeline 化分批提交（TASK-180）`（沿仓库 feat 先例，禁性能改善措辞入 subject）；C-02 台账笔 `docs(mailbox): 登记 TASK-180 实施验收与台账闭环（TASK-180）`。
4. **handoff.md**（沿 TASK-179 同构）：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（定档读数、语义门 (a)(d)(e)、变异红、前后耗时绝对数字）/ 逐门实测表 / token 前后读数 / 未覆盖项与不得推出的结论 / 提交表（显式哈希，禁时效指针）。

---

## 7. 收口记录（执行侧 2026-10-08 纯追加，不改写上文任何预注册口径）

- **三支归属**：**PASSED**（语义门 (a)(d)(e) 全绿，变异红两项全捕获，500/1000 定档 500，Checkstyle 811 未增，offline 450 不减）。环境两路可达 ⇒ 无 UNDETERMINED 分支。
- **实施关键读数**：
  - 定档实测：500 档（12 批，338.5772 ms）vs 1000 档（6 批，247.0058 ms），耗时差异仅 91.5714 ms 处于噪声区间，定档 500（单批连接占用短、缓冲小）；
  - 生产方法同负载（2000 records × 50 赞 = 100 000 行）：逐条往返基线 3 轮合计 14231.5039 ms（独立基线轮 15939.6786 ms），改造后生产方法 3 轮合计 574.8951 ms；
  - 语义门：(a) 全量收敛 200/200 违规 0；(d) 运行时排他等待 3016 ms 第二锁被拒，静态源码断言 pipeline 提交在锁 try 块内；(e) MONITOR 捕获 600 条命令流，SET->DEL->SADD 三元组严格保序，无 MULTI/EXEC 事务包装；
  - 防御分支：空成员记录仅执行 SET 与 DEL，不发射 SADD；
  - 变异红测试：抽 DEL 与乱序 SADD 注入均产生收敛数 0，成功被收敛门捕获；
- **门禁**：offline 七模块 `36/41/33/127/144/59/10` = **450 恒等**、rc=0（经用户授权将 `RecordLikeServiceTest.java` 纳入白名单并更新单测断言以适配 `executePipelined` 分批提交）；`--static=record-service` 从 814 降至 811（净减 3，不增）；IT 直跑 9/9 rc=0，缺变量跳过 0 项 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针四形态 HIT rc=0；`git diff --check` rc=0；tasks.json 全勾语法 rc=0；受保护 token 29 项只增不减。
- **偏差登记（详见 handoff §1）**：经用户授权将 `RecordLikeServiceTest.java` 纳入白名单并更新单测断言以校验 `executePipelined` 分批提交（恢复 offline 450 基线）；继承 TASK-179 未覆盖项（连接占用维度未覆盖、(b) 桶并发写窗口为 0 判别力、CLIENT KILL 服务端硬中断不支持）；scratch MySQL 建表补 `archived` 列与 `request_id` 字段；宿主 6379 竞争沿临时 16379 override 映射，测毕还原。
- **未达外部门槛**：全部读数限定本机隔离环境，不写百分比，不外推生产收益，未 push。
