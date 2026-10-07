# TASK-178 handoff：measure-like-write-path-capacity 点赞写路径容量与对账收敛测量

> 状态：**已收口（两笔本地提交，未 push）**。§0 开工读数；§1 偏差登记；§2–§10 为结论、清单、测量证据、逐门、机制归因、token、未覆盖项、提交与勘误。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。
> 原始证据为 `docs/perf/data/raw/task178-*`（gitignore，未入库）与仓库根 `task178-*.tmp` 临时脚本（用毕删除）；关键读数已内联本文件与 `docs/perf/测量-like-写路径-容量与对账.md`。

## 0. 开工规程与读数逐位核验（Level A）

- 开工 HEAD（派发笔）：`fac503b02536d819fd23d1dc46338a2757aec889`，`git log -1 --format=%s` = `docs(spec): 派发 TASK-178 点赞写路径容量与对账测量提案与任务书`；
- `git rev-list --left-right --count origin/main...main` = **`0 1`**；`git status --porcelain` 为空——零残留核验通过；
- 开工前无并发 java 进程（`tasklist` 读数入 `task178-g0-baseline.log`）；
- PLAN.md 29 项受保护 token 开工实测与任务书 §5 **逐位一致**（`TOKEN_VIOLATIONS=0`，详 §7）；
- 在途契约门 `bash scripts/verify/mailbox-contract.sh --open TASK-178 --baseline=fac503b02536d819fd23d1dc46338a2757aec889` **开工态实测 rc=0**；
- 基线离线测试与静态门（`task178-g0-baseline.log`）：
  - offline 七模块 `bash scripts/verify/mvn-verify.sh --mode=offline test`：`36/41/33/127/144/59/10` 全绿（共 **450**），Failures/Errors/Skipped 全 0，BUILD SUCCESS，rc=0；
  - Checkstyle `bash scripts/verify/mvn-verify.sh --static=record-service`：rc=**1**、`You have 814 Checkstyle violations`，严格 814 持平（≤814 达标口径）；
  - 词面门四形态（default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）全 **ZERO_HIT rc=1**，正向探针四形态全 **HIT rc=0**（`task178-g0-wordgate.log`；首轮 G0 内嵌形态因 `--untracked` 误置于 pattern 之后报 rc=128，已按 PLAN L4 harness 红线第 ③ 条订正复跑，见 §1.4）。
- 环境核验：scratch MySQL 可达（容器 `sport-verify-mysql`，宿主 **3307**，服务端 **8.0.46**）；受控真实 Redis 可达（**16379** 通道，容器 `sport-verify-redis`，redis **7.2.16**，`run_id dcbb35cfd518529adfcb8a54b778ec2c10b3f9b0`）——宿主 6379 存在竞争，处置见 §1.2；两路均可达 ⇒ **无 UNDETERMINED 分支**，读数全部实测取得，无伪造、无重试刷绿。

## 1. 偏差登记

### 1.1 IT 排空轮判别式误设（测量面缺陷，非被测语义异常；已修正并保留首轮证据）

1. 首轮 IT 直跑 `IT_RUN_RC=1`，`Tests run: 7, Failures: 3`——三条失败**全部落在同一项** `roundsEqualsBatchesPlusOne`（我预注册的预期：排空轮 = 批次 + 1）。
2. 原始读数（`task178-e1-*-rep1.attempt1-oracle-defect.json`）显示三形态均 **`rounds == batchCount`**（M1 25/25、M2 38/38、M3 10/10），且**其余 12 项闭合检查全为 true**（`rangeTotal`/`trimTotal`/`perBatchTrimEqualsRange`/`rowsDeltaMatchesAffected`/`llFinalZero`/`finalRowsMatchModel` 等）。根因：排空循环在每次 `flushPendingLikes()` 后立即复查 `LLEN`，队列在"消费掉最后一批"的那一轮即归零并 break，**不存在额外的空跑轮**——是我把判别式的算术多算了 1。
3. 处置：不改被测代码、不改读数，只修判别式为 `roundsEqualBatchCount`（该式仍具鉴别力：若某轮被锁静默跳过，则 `rounds > batchCount` 照样判红）。复跑 `IT_RUN_RC=0`、`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。**首轮日志与三份 rep1 原文均未抹除**（`task178-it-run.attempt1.log` + `*.attempt1-oracle-defect.json`）。
4. 判定影响：按任务书 §0.9「测量轮内发现语义异常 ⇒ FAILED 支如实登记」的适用对象是被测语义；本项异常发生在测量工具自身，且修正后闭合断言全绿 ⇒ E1 仍归 **PASSED**，本条作为测量面偏差登记，不记 FAILED。

### 1.2 宿主 6379 端口竞争与 16379 受控通道（沿既有先例）

1. 本机原生 `redis-server 3.0.504` 常驻监听 6379，宿主侧任何 6379 连接都命不中受控容器（双探针实测：6379 → `redis_version:3.0.504`；16379 → `7.2.16` 且与容器内 `run_id` 同值）——若直连 6379 将测在错误实例上，属任务书 §0.6 禁止的伪造读数风险。
2. 处置沿 TASK-110/TASK-152 先例：以仓库根临时 `task178-compose-override.tmp`（`services.redis.ports: ["16379:6379"]`）对**既有**容器 `sport-verify-redis` 追加宿主端口，`docker compose -f docker-compose.yml -f <override> up -d redis`，rc=0；未新建容器、未停用户进程、未改基线 compose 文件本体。
3. 实例归属双侧互证（`task178-channel.log`）：宿主侧 16379 `INFO server` 与容器内 `redis-cli INFO server` 同为 `run_id dcbb35cf…` / `tcp_port 6379`。IT 侧再落一份 `task178-it-run-meta.json`（`redisRunId` 同值）作为测量内自证。
4. 通道生命周期：测量与收口门禁 IT 复跑结束后，以基线 compose 重建映射（`docker compose -f docker-compose.yml up -d redis`）并删除 override 临时文件；还原读数见交付汇报。

### 1.3 IT 无法经 harness `--it` 分支承载（Notice）+ 直跑例外

`scripts/verify/mvn-verify.sh` 的 `--it` 分支硬编码 `IT_MODULE=leaderboard-service` 与三个 leaderboard IT 类名（脚本 L32-34），record-service 新 IT 无法经该分支承载。按任务书 §0.2 与 TASK-177 Notice 先例，以镜像 harness offline 纪律的完整命令直跑并留证（完整命令 + 退出码入报告 §9 与本文件 §5 G3）。harness 缺口继续登记为 **Notice**，供指导侧评估参数化 IT 入口；本任务未改动 `scripts/`。

### 1.4 工具链与探针写法（执行工具侧，已订正）

1. **词面门 pattern 前置**：G0 首轮把 `--untracked` 置于 pattern 之后，git 2.20.1 视其为 revision ⇒ `fatal: unable to resolve revision: --untracked`、rc=128。按 PLAN.md L4 已登记的 harness 红线第 ③ 条订正为 `git grep --untracked -n -I -iE "$PATTERN" -- <excludes>`，并采用**三态判定**（rc=0 命中 / rc=1 无命中 / 其他 rc 判失败不判通过）。订正后四形态 ZERO_HIT rc=1 + 探针 HIT rc=0。
2. **预检探针 rc=124**：首轮 Redis 探针用 `cat <&3` 读套接字，服务端不主动关闭 ⇒ 读到超时（`PROBE16379_RC=124`），身份信息已在超时前取到；次轮补 `QUIT` 后 rc=0。属探针写法，不影响任何测量读数。
3. **Bash 入口纪律**：全部 bash 均以 `D:\git\Git\bin\bash.exe` 执行仓库根 `*.tmp` 脚本文件（`BASH_VERSION=4.4.23(1)-release`、git `2.20.1.windows.1`），脚本内 `exec > <log> 2>&1` 落盘；临时脚本与消息文件用毕删除。

### 1.5 契约门 C-02 在途 rc=1：历史回传交叉触发 + 本任务自身判据 B 因清单书写形态判红（实质足迹与白名单全等，另证）

1. **读数**：开工态 rc=0；C-01 在途 rc=**0**；C-02 在途（已暂存 4 条台账路径后）rc=**1**，判据 A=0、判据 B=1；失败任务共 **71** 条（与 TASK-177 §1.2 的 C-02 期同量级）。其中**包含 TASK-178 自身一行判据 B 失败**——这一点与 TASK-177 不同（该轮自身判据 B 通过），故单列登记，不写作"仅历史交叉触发"。
2. **本任务判据 B 判红的根因（两条，均为路径形态问题，非越界改动）**：
   - **清单多报**：任务书 §4 白名单（派发笔，执行侧零触碰、不可改写）以短名与散文形式书写——`` `tasks.json` 闭环勾选``、`` `work/mailbox/PLAN.md` 纯追加`` 中的 `tasks.json`、`PLAN.md`，以及「禁触」句里的 `RecordLikeService.java`，被工具当作三条"已声明改动"路径比对，故报"清单多报（实际未改动）"三条；其中 `RecordLikeService.java` 出现在**禁触**条款中，工具按声明项处理属解析口径问题。
   - **改动集未声明**：任务书 §2.5 规定的报告文件名含非 ASCII 字符（`docs/perf/测量-like-写路径-容量与对账.md`），`git diff --name-only` 在 `core.quotepath` 默认开启下输出八进制转义带引号形式（`"docs/perf/\346\265\213…"`），与白名单里的明文 UTF-8 路径无法逐字匹配 ⇒ 该条被判"未声明"。其余 6 条路径均正常匹配。
3. **实质符合性另证（Level A）**：本轮实际足迹 = `git diff --name-only <派发笔> --cached` + untracked = **恰 7 条**，与 §3 只改清单逐条全等；`git diff --name-only <派发笔> --cached -- '*/src/main/*'` 命中数 **0**，`git status --porcelain -- '*/src/main/*'` 为空 ⇒ `RecordLikeService.java` 与全部 `src/main` 零触碰（"清单多报 RecordLikeService.java"恰与事实同向：该文件确实未改）。
4. **处置**：白名单与报告文件名均由派发笔固定，执行侧无权改写 ⇒ 不试图让该门"变绿"，按任务书 §0.9/§6.1 如实登记；收口无参门禁（足迹并入提交后 ACTUAL 空集）预期 rc=0，实测见交付汇报。harness 侧建议（不在本任务实施）：契约工具比对前统一 `core.quotepath=false`，且只改清单应使用仓库根相对全路径逐行代码块（TASK-177 §3 形态）而非散文短名。

## 2. 一句话结论

**完成**：TASK-178 首轮纯测量与语义审计收口——`src/main` 零触碰（`RecordLikeService.java`、`RecordLikeMapper`、`app.like.flush-batch`、两个 `@Scheduled` 周期、锁与幂等语义一字未改），未引入背压/告警/参数变更。三支判定 **E1 / E2 / A1 均归第一支（PASSED）**：E1 三形态 × 3 轮（9 轮）闭合断言全绿，实测纯处理能力 4237–14845 队列元素/秒，与 40 ops/s 算术上界分口径登记，据判别式「排空时延由 `min(上界, 实测)` 决定」定位写路径约束在调度节拍与批次上限而非 DB 写或 Redis 往返；E2 三档 × 2 轮（6 轮）结构性计数全绿，对账成本由逐 record 三次 Redis 往返主导（R3 100 万行写段占 96.3%、全表载入仅 3.0%，每 record 均摊 ≈1.7ms、每次往返 ≈0.57ms），稳态轮无增量跳过；A1.1 单轮收敛与 A1.2 两跳收敛实测通过，A1.3 DEL+SADD 非原子窗口按代码级审计登记（未注入并发 ⇒ 未覆盖）。门禁读数：开工态与 C-01 在途契约门 rc=0；**C-02 在途契约门 rc=1**——71 条历史回传交叉触发之外，**本任务自身判据 B 亦判红**，根因是派发笔白名单以短名/散文书写（`tasks.json`、`PLAN.md`，且禁触句的 `RecordLikeService.java` 被当声明项）加上 `core.quotepath` 对非 ASCII 报告文件名的转义，属路径形态而非越界改动：实际足迹恰为白名单 7 条、`src/main` 命中 0（详 §1.5，不试图刷绿）；其余门禁（offline 450、static 814 持平、IT 7/7 rc=0、词面门四形态、空白门、token 只增不减、纯 LF）全项实测通过。两笔提交本地落盘，**未达外部门槛**。全部数字限定本机隔离环境，不声称线上延迟或生产容量改善。

## 3. 只改清单（与 `git diff --name-only fac503b02536d819fd23d1dc46338a2757aec889` 逐条比对）

```
docs/perf/data/exp-like-write-path-capacity.json
docs/perf/测量-like-写路径-容量与对账.md
record-service/src/test/java/com/sportverify/record/service/RecordLikeWritePathCapacityIT.java
spec/changes/measure-like-write-path-capacity/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-178/handoff.md
work/mailbox/tasks/TASK-178/spec.md
```

前 3 条为 **C-01 证据笔**（3 files / **+1903 −0**：IT 新文件 +1184、测量报告 +243、机器摘要 JSON +476；哈希 `697be1f8ae77d04a24fe421044be07fdd2cb1521`）；后 4 条为 **C-02 台账笔**（`tasks.json` 10 步 completed + 3 分组 passes 全 true、本任务书末尾「收口记录」纯追加、本回传、`PLAN.md` 纯追加验收记录与 29 项 token 追踪表）。收口终检两笔合计恰上列 7 条，与任务书 §4 白名单完全一致。显式零触碰：`src/main/**`（含 `RecordLikeService.java` 与全部配置）、既有迁移脚本、其他 `spec/changes/` 目录、`.codex/`、`.trae/`、`scripts/`、pom 模块。

## 4. 测量证据与三支判定（Level A）

### 4.1 环境与正交性

- scratch 库 `task178_it`：`sql/02-record-db.sql` 机械改名 `record_db → task178_it` 于跑前重建（`DROP DATABASE` + 逐语句灌入），连接后强校验 `SELECT DATABASE() = task178_it`，跑毕 `DROP DATABASE`；演示 `record_db` 与用户数据卷零触碰。
- Redis：专用 **DB 12**，键前缀 `like:` / `lock:like:`，每形态/每档跑前后 `KEYS`+`DEL` 清理，`@AfterAll` 再清一遍；DB0 演示键零触碰。
- 装配与生产同构：mapper 走 `MybatisSqlSessionFactoryBean` + `SqlSessionTemplate`（使 flush 的 `TransactionTemplate` 内 `batchInsertIgnore` + `batchDelete` 真共用一个本地事务，`mapUnderscoreToCamelCase=true` 与生产配置一致）；锁走真实 Redisson（`tryLock(3, -1, SECONDS)` + 30s 看门狗，防重语义未降级）；`flushBatch` 取被测类字段初始化值 **200**（与 `app.like.flush-batch` 默认同值）。计时以 JDK 动态代理包层（`opsForList/opsForValue/opsForSet/delete` + mapper + 事务管理器），不改动被测类。
- 队列元素沿生产 JSON 形态 `recordId/userId/action/enqueuedAt`，经 RPUSH 注入 `like:pending:ops`。

### 4.2 E1 flush 服务率三形态 × 3 轮（结构计数逐轮全等）

| 形态 | seed 元素 | 批次=轮次 | LRANGE 总量 | LTRIM 总量 | 净 likes/unlikes | INSERT/DELETE 影响 | 行数增量 | 终态 LLEN | 排空墙钟 ms（3 轮） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 5000 互异全 LIKE | 5000 | 25 | 5000 | 5000 | 5000 / 0 | 5000 / 0 | 5000 | 0 | 1180.09 / 784.49 / 689.17 |
| M2 2500 抵消对 + 2500 纯 LIKE | 7500 | 38 | 7500 | 7500 | 2500 / 2500 | 2500 / 0 | 2500 | 0 | 549.02 / 509.19 / 512.82 |
| M3 2000 条仅 100 互异 key | 2000 | 10 | 2000 | 2000 | 50 / 50 | 50 / 0 | 50 | 0 | 164.04 / 134.72 / 207.98 |

- 13 项闭合检查在 9 轮中**逐轮全为 true**：`batchCountMatches`、`rangeTotalEqualsSeeded`、`trimTotalEqualsSeeded`、`roundsEqualBatchCount`、`perBatchTrimEqualsRange`、`rowsDeltaMatchesAffected`、`llFinalZero`、`likesInputMatchesExpected`、`unlikesInputMatchesExpected`、`likesAffectedMatchesExpected`、`unlikesAffectedMatchesExpected`、`rowsDeltaMatchesExpected`、`finalRowsMatchModel`。
- M2 揭示"无行可删幂等路径"：2500 个删除尝试影响 **0 行**（键从未落库），行数增量仍等于净插入 2500 ⇒ `DELETE` 天然幂等未产生负向行差。
- M3 揭示末次动作去重强度：2000 元素折 **100** 净操作（20:1），每批 200 折 10。
- 事务段：稳态批次均值 M1 20.44–27.31ms、M2 8.04–8.94ms、M3 7.85–13.02ms；同一 JVM **首批 194.65ms** 属冷启动段，按原值登记不取极值美化。非事务段（LRANGE/LTRIM 往返 + JSON 解析 + 去重 + 加解锁）rep3 为 M1 182.86 / M2 211.28 / M3 73.99 ms。

### 4.3 E2 对账成本三档 × 2 轮（单因素 = record 数，固定每 record 50 赞）

| 档 | records / rows | 载入 ms（r1/r2） | Redis 写段合计 ms（r1/r2） | 对账总墙钟 ms（r1/r2） | 写段占比 | 每 record 均摊 ms | 堆 MB（r1 前→后） | seed ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 400 / 20000 | 108.4 / 22.7 | 790.0 / 654.0 | 942.3 / 693.2 | 83.8% / 94.3% | 2.356 | 57.3→41.6 | 436.8 |
| R2 | 4000 / 200000 | 191.2 / 164.2 | 6371.0 / 6046.7 | 6668.7 / 6264.7 | 95.5% / 96.5% | 1.667 | 56.2→180.8 | 2237.0 |
| R3 | 20000 / 1000000 | 1070.0 / 877.3 | 34302.1 / 32215.6 | 35608.3 / 33295.7 | 96.3% / 96.8% | 1.780 | 73.1→531.3 | 11013.8 |

- 7 项结构检查在 6 轮中**逐轮全为 true**（`pairsLoaded==rows`、`distinctRecords==records`、`setCount==deleteCount==saddCount==records`、`saddMembersTotal==rows`、抽样三点计数/基数/DB 行数均 50）。
- **主导项判定**（机会总览 P2 要求先判"队列容量 / DB 写 / Redis 往返 / 全表驻留"何者主导）：行数 ×50（2 万→100 万）时载入段仅 ×9.9，而对账总墙钟 ×37.8 ⇒ 成本随 **record 数**线性、由每 record 三次 Redis 往返主导（R3 共 60000 次往返，均摊 0.572 ms/次）；全表驻留体现在堆（R3 +458.2MB / 100 万行）而非耗时主导项。
- **TASK-137 的 N+1 消除只在读侧**：写侧仍是 `3 × records` 次往返，本轮实测给出其成本份额。
- **稳态轮不省成本**：round2 与 round1 的 SET/DEL/SADD 次数与成员总数逐位相等（无增量跳过），耗时比 0.736 / 0.939 / 0.935。

### 4.4 A1 收敛语义审计

- **A1.1（实测通过）**：DB `{1,2,3}` + Redis 计数 `"999"` + 成员集 `{1,2,3,777}` → 一次 `reconcileLikeCounts()` 后计数 `"3"`、成员集 `{1,2,3}`、`SISMEMBER 777=false`（漂移用户被 DB 权威值抹平）。
- **A1.2（实测通过）**：DB `{1}` + Redis 计数 `"2"`/成员 `{1,2}` + 队列内一条未 flush 的 `LIKE(990002,2)`。逐跳：① 对账 → 计数回退 `"1"`、成员去 2、**`LLEN` 仍为 1**（对账不动 pending）；② flush → `LLEN=0`、DB 行数 2；③ 再对账 → 计数 `"2"`、成员 `{1,2}`。与实现注释声明的两跳收敛一致。
- **A1.3（登记，未实测）**：DEL+SADD 非原子窗口——逐 record 序列为 `SET count` → `DEL users` → `SADD members`，两次独立往返无 MULTI/Lua；并发 `like()` 的 SADD 落在 DEL 之前会被抹去（其 `INCR` 与 pending 不受影响 ⇒ 计数与成员集短暂不一致），落在重建 SADD 之后则保留；幂等闸门返回值可能因此翻转并多推一条 pending，该重复 pending 由末次动作去重 + `INSERT IGNORE` 联合主键吸收，不产生重复行；收敛依赖「下轮 flush + 再下轮对账」。**本轮未注入并发时序 ⇒ 该条为代码级推理登记，列为未覆盖**。

### 4.5 三支判定归属（任务书 §2.4 预注册）

- E1：9/9 轮闭合断言全绿 ⇒ **第一支（PASSED）**；§1.1 的三项红属测量工具判别式误设（首轮），修正后同判据复跑全绿，不落入 FAILED 支（FAILED 支的对象是"闭合断言失败或收敛反例"，被测语义未出现）。
- E2：6/6 轮结构计数全绿 + 曲线与外推边界登记 ⇒ **第一支（PASSED）**。
- A1：A1.1/A1.2 用例通过 ⇒ **第一支（PASSED）**；A1.3 未注入并发按任务书 §2.3 本就是登记项，不构成否决，列 §8 未覆盖。
- 无 UNDETERMINED：MySQL 与受控 Redis 均可达，未使用规程外手段，无伪造读数。

## 5. 逐门实测退出码

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工读数 | 任务书 §3 开工态逐位核验 | HEAD `fac503b0…`、`0 1`、工作树零残留、token 29/29 逐位一致、契约门开工态 rc=0 | 过 |
| G1 offline 全模块（开工基线） | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**，`36/41/33/127/144/59/10`、全仓 **450**、Failures/Errors/Skipped 全 0、BUILD SUCCESS | 过 |
| G2 Checkstyle（开工基线） | `bash scripts/verify/mvn-verify.sh --static=record-service` | rc=**1**，严格 **814** 持平（≤814 达标口径；`src/test` 不在扫描面：checkstyle-result.xml 26 个文件全来自 `src/main`，新 IT 零贡献） | 过 |
| G3 IT 直跑 | §1.3 直跑例外（harness `--it` 无法承载） | 次轮 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`、rc=**0**、BUILD SUCCESS（132.8s）；首轮 rc=1/3 失败见 §1.1，原文留证 | 过 |
| G3b IT 缺变量跳过 | 不导出 `TASK178_IT_*` 复跑同一命令 | `Running …RecordLikeWritePathCapacityIT` → `Tests run: 0`、rc=0，**不记通过**（`task178-it-skip.log`） | 过（按未覆盖记账） |
| G4 词面门 | 正则自 ci.yml 现场提取（58 字节），四形态 `git grep --untracked -n -I -iE` + 三态判定 | C-01 前四形态全 **ZERO_HIT rc=1**、探针四形态全 **HIT rc=0**、探针文件已删（`PROBE_GONE`）；新增三文件单独复扫 0 命中 | 过 |
| G5 契约门 | 开工态 / C-01 在途 / C-02 在途 / 收口无参 四段读数 | 开工态 rc=**0**；C-01 在途 rc=**0**（`CONTRACT_OPEN_RC=0`，判据 A 两件套齐 + 判据 B 清单一致）；C-02 在途 rc=**1**（判据 A=0、判据 B=1；失败 71 条历史回传，**含 TASK-178 自身判据 B**，根因见 §1.5：短名/散文清单 + `core.quotepath` 转义；实质足迹 7 条与白名单全等、`src/main` 命中 0）；收口无参 rc 见下方收口终检 | 判据 A 过；判据 B 在途未过 ⇒ §1.5 登记，不判通过 |
| G6 空白门 | `git diff --check` 与 `git diff --cached --check` | 均 rc=**0** | 过 |
| G7 只改清单 | `git diff --name-only fac503b0…` + untracked 比对 §3 | C-01 恰 3 条、C-01+C-02 合计恰 §3 全 7 条，与任务书 §4 白名单全等 | 过 |
| G8 红线（零生产改动/工具链） | `src/main/**` 零改动；无背压/告警/参数变更；无新插件；bash 仅红线入口脚本文件；禁 push/`add -A`/stash | IT 仅 `src/test` 新文件；逐笔 `git add -- <显式路径>`（暂存清单核对为 3 条 A）；全仓 diff 无生产源码；临时脚本用毕删除 | 过 |
| G9 受保护 token | PLAN.md 行命中数（`grep -cF`）只增不减 | 开工 29 项与任务书 §5 逐位一致（`TOKEN_VIOLATIONS=0`）；C-02 追加后逐项 +1（§7） | 过 |
| G10 tasks.json 语法 | `python -c "import json;json.load(...)"` | rc=**0**（`TASKS_JSON_OK`） | 过 |

收口终检（C-02 后亲跑，输出见交付汇报）：offline 七模块 450 恒等分模块逐位、`--static=record-service` 不增、IT 直跑全绿 rc=0、契约门无参 rc=0、`git diff --name-only fac503b0…` 与 §3 逐条全等、`git status --porcelain` 为空、`git rev-list --left-right --count origin/main...main` 终态读数、PLAN.md 自派发笔起纯追加（`git diff` 仅追加无删除）。

## 6. 机制归因与口径要点

1. **三口径分列（任务书 §0.7 硬要求）**：① 算术上界 `40 ops/s = flushBatch 200 / fixedDelay 5000ms`——配置推导的结构性上界，**不是实测**；② 实测纯处理能力 `4237–14845 元素/秒`——IT 绕开节拍循环直调 flush 的排空速率；③ 有效服务率——单实例受节拍封顶，`min(①,②) = ①`。三者在本报告与 JSON 分节登记，未以算术值冒充实测吞吐。
2. **写路径约束定位**：②≫① ⇒ 排空时延由**调度节拍与批次上限**决定，不由 DB 写或 Redis 往返决定。派生（Level B 算术，非实测）：5000 条积压按 ① 需 25 tick ≈ 125s，而本机 ② 只需 0.69–1.18s。持续到达率超 ① 时 `LLEN` 无上界增长，与 flush 内部快慢无关。
3. **对账成本结构**：总成本 ≈ `3 × records` 次 Redis 往返（本机 ≈0.57ms/次）+ 一次全表载入；载入随行数增长但份额小（R3 3.0%），写段份额 96.3% ⇒ 若后续要降低对账成本，判别对象应是**每 record 往返次数**而非 SQL 行数（本任务不实施，见 §8）。
4. **一致性语义的可测边界**：A1.2 的三跳实测把实现注释里"pending 被 DB 权威值覆盖、两跳收敛"的散文断言转成了可复跑证据（计数暂时回退、`LLEN` 不被对账触碰、flush 后再对账收敛）；A1.3 的窗口则是同一机制在**并发**下的延伸，本轮未测。
5. **测量正交性设计**：每形态/每档独立 `TRUNCATE` + 键清理；E1 结构计数在 3 轮间逐位相等（IT 内作相等断言）；E2 round2 与 round1 结构计数逐位相等；耗时项按原值登记（含冷启动首批），不取极值、不重复刷绿。

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`）

29 项基线与终态对照（基线值 = 任务书 §5 且开工实测逐位一致；终态值 = C-02 PLAN.md 追加后实测。追加段含 29 行 token 追踪表 ⇒ 每项恰 +1；全部满足只增不减）：

| Token | 基线值 | 终态值 | 变动 |
| --- | --- | --- | --- |
| 13.4 | 18 | 19 | +1 |
| 18.0 | 20 | 21 | +1 |
| 73.93 | 19 | 20 | +1 |
| 68.8 | 19 | 20 | +1 |
| 6315 | 16 | 17 | +1 |
| 1.8612 | 15 | 16 | +1 |
| 3.3066 | 15 | 16 | +1 |
| 5.7056 | 15 | 16 | +1 |
| 9.408 | 15 | 16 | +1 |
| 36525962432 | 15 | 16 | +1 |
| 36586847965 | 14 | 15 | +1 |
| 36438897772 | 15 | 16 | +1 |
| 36399582548 | 14 | 15 | +1 |
| 36098038547 | 14 | 15 | +1 |
| 2806 | 21 | 22 | +1 |
| 598 | 14 | 15 | +1 |
| 36736221648 | 13 | 14 | +1 |
| 36808102571 | 8 | 9 | +1 |
| 36821040708 | 6 | 7 | +1 |
| 36845152965 | 5 | 6 | +1 |
| 36871294588 | 5 | 6 | +1 |
| 36880083885 | 6 | 7 | +1 |
| 36958994260 | 6 | 7 | +1 |
| 36976873215 | 6 | 7 | +1 |
| 36992632143 | 5 | 6 | +1 |
| 36995450125 | 3 | 4 | +1 |
| 37008317295 | 4 | 5 | +1 |
| 37021305016 | 5 | 6 | +1 |
| 37591580687 | 4 | 5 | +1 |

受保护 token 集合规模仍为 **29 项**（本轮无新增：第 29 项 `37591580687` 由 TASK-176 期登记）。本文件与 PLAN / spec 追加段均不含词面门正则字面量与敏感词。

## 8. 未覆盖项与不得推出的结论（任务书 §0.1/§2.3/§2.4 如实登记）

1. **无线上/生产测量**：全部读数来自本机、scratch 库 `task178_it`、专用 Redis DB12、单实例、无并发访问 ⇒ 不得外推为线上延迟、业务耗时或吞吐改善；不计算任何百分比"改善"值。
2. **未注入并发时序**：A1.3 DEL+SADD 窗口、多实例 `lock:like:flush` / `lock:like:reconcile` 竞争、并发 `like()` 与对账交错均未实测；§6.3 与 A1.3 的表述为代码级推理，不得当作已证明。
3. **未测热路径端到端**：`like()` / `unlike()` / `getLike()` 单次调用延迟与 SADD/INCR/RPUSH 三写的事务外开销不在本轮 ⇒ 不得推断点赞接口 P99。
4. **未测积压增长形态**：未在 5s 节拍下做真实到达率压测与 `LLEN` 斜率测量；§6.2 的排空时长为算术推导（Level B）。既有双 Gauge（`like.pending.queue.size` / `like.pending.head.age.ms`）与 1000 阈值 warn 的行为未在本轮复验。
5. **未测失败路径**：DB 写失败 ⇒ 不 LTRIM ⇒ 整批重放的故障注入未做（trim 不前置于 commit 的反例保护既有用例，本任务未复验）；`pushPending` 失败仅记日志的路径未注入。
6. **规模与实例边界**：E2 止于 100 万行 / 20000 records；Redis 仅 7.2.16 单实例，未覆盖集群/哨兵与不同版本；`record_like` 无二级索引前提下的全表扫描语义外推未验证。
7. **未测量比值不得引用他任务数字**：本报告不引用 TASK-137/174 的历史数字充当本轮结论，仅引用其代码事实与登记口径。
8. **外部门槛**：`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（未 push；push 须用户显式单次授权）。
9. **不得推出的结论与止损**：不得据本轮调整 `flush-batch`、`@Scheduled` 周期或引入背压/告警/pipeline 化/对账增量化；若指导侧评估要改，须另立提案与授权（任务书 §0.9）。本轮测量未观察到需要 FAILED 登记的被测语义缺陷。

## 9. 提交

| 提交 | 内容 |
| --- | --- |
| C-01 `697be1f8ae77d04a24fe421044be07fdd2cb1521` | `test(record): 新增点赞写路径容量与对账测量 IT 与本机读数报告（TASK-178）`（IT 新文件 +1184、测量报告 +243、机器摘要 JSON +476；3 files / +1903 −0） |
| C-02（哈希以 `git log` 实测为准，读数见交付汇报） | `docs(mailbox): 登记 TASK-178 验收记录与测量闭环（TASK-178）`（tasks.json 10 步 completed + 3 passes 全 true、任务书收口记录纯追加、本回传、PLAN.md 验收记录与 29 项 token 表纯追加） |

## 10. 勘误登记

本笔无勘误。若后续独立复核退回订正，沿 TASK-176 C-03/C-04 先例以纯台账修正笔追加。
