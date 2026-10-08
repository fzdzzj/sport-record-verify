# TASK-180 Handoff

## 0. 编号、基线与外部状态

- **任务编号**：`TASK-180`（impl-like-reconcile-pipeline，课题 5 第三轮：对账 pipeline 化实施）。
- **派发基线**：`813ca9989486a75352ff409c84d771e5e2020b1c`（指导侧亲笔，零触碰）。
- **状态**：**已收口**。裁决＝**PASSED**（语义门 (a)(d)(e) 全绿，变异红全捕获，500 档定档入生产代码，Checkstyle 降至 811 未增，offline 450 不减）。
- **外部环境**：未 push、未建 PR、未达外部门槛。隔离 scratch MySQL 与受控 Redis DB 13 通道测毕已清理还原。

## 1. 开工规程核验与偏差登记

### 1.1 开工规程核验

- **HEAD 状态**：`813ca9989486a75352ff409c84d771e5e2020b1c`，`origin/main...main` = `0 1`，工作树 `git status --porcelain` 为空。
- **基线读数**：离线测试 450 逐位（`36/41/33/127/144/59/10`），Checkstyle `--static=record-service` 基线 814，在途契约门 `--open TASK-180 --baseline=813ca99…` rc=0。
- **词面门**：提取 ci.yml 现场 58 字节正则，四形态全 ZERO_HIT rc=1，探针四形态 HIT rc=0，探针删除后 gone。
- **Token 29 项**：开工 `grep -cF` 实测逐项登记，全部与基线一致。

### 1.2 偏差登记

1. **Redis 宿主端口竞争处置**：宿主 `6379` 存在原生进程监听竞争，沿用 TASK-178/179 先例，通过临时 compose override 文件向既有容器 `sport-verify-redis` 追加宿主端口 `16379`（`run_id` 互证为 `2b6a429ed450fde29f1a5f7cf1bcfc2337a00450`）。测算完成后已通过原生 compose 重新拉起还原为默认 `6379` 映射，并删除临时文件。
2. **scratch MySQL 表结构补全**：`sql/02-record-db.sql` 历史建表缺少 `sql/05-track-point-archive-shards.sql` 引入的 `archived` 列与 `request_id` 列，IT 初始化 scratch 库时自动补全，未改动生产 schema 与代码。
3. **继承未覆盖项（延续 Notice）**：
   - 连接占用维度未覆盖（Redisson 单连接独占时隙未做微秒级精细采样）；
   - (b) 桶并发写窗口为 0 判别力（持排他锁期间并发写拦截尝试数为 0，结论基于并发控制类别）；
   - CLIENT KILL 服务端硬中断不支持（依赖幂等重写与下轮对账收敛）。
4. **单测断言调整纳入白名单**：离线单测 `RecordLikeServiceTest.reconcile_singleBatchQuery_fixesRedisFromDb` 针对旧实现逐条 mock 断言（`valueOps.set` 与 `setOps.add`），生产代码 pipeline 化后改为分批 `executePipelined` 提交，单测产生 1 处失败（449/450）。经用户明确授权，将 `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java` 纳入白名单并同步调整为校验 `verify(redis, atLeastOnce()).executePipelined(any(RedisCallback.class))`，离线测试 450 恢复全绿。

## 2. 一句话结论

在受控真 Redis + scratch MySQL 隔离环境下，完成生产代码 `RecordLikeService.reconcileLikeCounts` 的 pipeline 化分批改造（循环体解耦为构造段与提交段两层结构，引入 `PIPELINE_BATCH_SIZE = 500`）：
**语义门 (a) 全量收敛 200/200 违规 0 项，(d) 运行时持锁排他等待 3016 ms 第二锁被拒且源码静态断言 pipeline 提交在锁 try 块内，(e) MONITOR 捕获 600 条写命令流严格保序且无事务包装；抽掉 DEL 与乱序 SADD 两处变异红全部被收敛门捕获；定档预试 500 vs 1000 两档差异 91.5714 ms 在噪声内，定档 500 保守默认；同负载 2000 records × 50 赞直跑 3 轮耗时由逐条往返的合计 14231.5039 ms 降为 574.8951 ms；三支判定裁决为 PASSED**。

## 3. 只改清单

- docs/perf/data/exp-like-reconcile-pipeline-impl.json
- docs/perf/实施-like-对账-pipeline-化.md
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeReconcilePipelineIT.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- spec/changes/impl-like-reconcile-pipeline/tasks.json
- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-180/handoff.md
- work/mailbox/tasks/TASK-180/spec.md

## 4. 实施证据

### 4.1 定档预试（500 vs 1000，同负载 2000 records × 50 赞）

| 批容量候选 | 提交批次 (flushes) | 墙钟耗时 (ms) | 绝对差异 (ms) | 定档依据 |
| --- | --- | --- | --- | --- |
| 500 | 12 | 338.5772 | 基准 | **定档默认**：两档差异仅 91.5714 ms 处于噪声区间，500 档连接持有时间短、输出缓冲峰值小 |
| 1000 | 6 | 247.0058 | 91.5714 | 备选：批次较少但单批缓冲需求增大 |

### 4.2 语义门直测复验

- **(a) 全量收敛态**：200 records × 50 赞，前置注入 200 幽灵成员与错计数；生产直跑耗时 261.2283 ms；全量比对违规记录 0，收敛记录 200，部分更新 0。通过。
- **(d) 锁语义**：运行时第二把锁调用 `tryLock(3, -1, SECONDS)` 被拒，等待 3016 ms；静态源码断言 `hasTryLock=true`, `hasUnlock=true`, `hasPipelined=true`, `staticCallInsideLockTry=true`。通过。
- **(e) 命令序一致性**：MONITOR 捕获 600 条写命令流（SET 200, DEL 200, SADD 200）；逐 record 为 SET -> DEL -> SADD 严格次序；`transactionWrappers` 为空（无 MULTI/EXEC）。通过。

### 4.3 变异红测试判别力

- **变异红 1（抽掉 DEL）**：幽灵成员未被清空，实测收敛数 `0 / 200`（`setOnly=200`），成功被收敛门捕获。
- **变异红 2（乱序 SADD/DEL）**：重建成员被随后的 DEL 抹除，实测收敛数 `0 / 200`（`setOnly=200`），成功被收敛门捕获。

### 4.4 防御分支（空成员记录）

针对点赞数为 0 的记录（仅有运动记录无点赞行）：MONITOR 捕获其仅发射 SET 与 DEL，未发射 SADD 命令，与改造前防御语义完全一致。

### 4.5 生产方法同负载服务率量化（绝对数字，无百分比、无外推）

同数据同负载（2000 records × 50 赞 = 100 000 行），生产方法直跑 3 轮对照：

| 形态 | 3 轮各轮耗时 (ms) | 3 轮合计耗时 (ms) |
| --- | --- | --- |
| 改造前逐条往返基线 | 5137.6391 / 4264.3059 / 4829.5589 | 14231.5039 |
| 改造后生产 pipeline 方法 | 225.2709 / 182.7872 / 166.8370 | 574.8951 |

*(注：同数据同负载直跑，仅登记绝对数字，不计算百分比，不外推生产收益)*

## 5. 逐门实测退出码

| 门禁项 | 判据与命令 | 实测读数 | 结果 |
| --- | --- | --- | --- |
| G0 开工基线 | HEAD=813ca99…，0 1，工作树干净 | 逐项核验通过 | 通过 |
| G1 离线测试 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | `36/41/33/127/144/59/10` = 450 全绿，rc=0（经用户授权同步调整 RecordLikeServiceTest 断言以校验 executePipelined 分批提交） | 通过 |
| G2 静态门禁 | `bash scripts/verify/mvn-verify.sh --static=record-service` | 811 Checkstyle violations（基线 814，净减 3），rc=1 | 通过（不增） |
| G3 IT 直测 | `mvn ... -Dtest=RecordLikeReconcilePipelineIT`（设置 TASK180_IT_*） | Tests run: 9, Failures: 0, Errors: 0, rc=0 | 通过 |
| G3b IT 缺变量 | `mvn ... -Dtest=RecordLikeReconcilePipelineIT`（无 TASK180_IT_*） | Tests run: 0, Failures: 0, Errors: 0, rc=0 | 通过（记未覆盖） |
| G4 词面门 | 提取 ci.yml 正则，四形态 + 正向探针 | 四形态 ZERO_HIT rc=1，探针 HIT rc=0，PROBE_GONE | 通过 |
| G5 契约门 | 开工在途 rc=0；收口无参 `bash scripts/verify/mailbox-contract.sh` | 收口无参 rc=0 | 通过 |
| G6 空白门 | `git diff --check` / `git diff --cached --check` | 无空白违规，rc=0 | 通过 |
| G7 白名单足迹 | 9 路径逐项比对 | C-01 恰 4 条，C-02 恰 5 条（含经授权单测调整），合计 9 条 | 通过 |
| G8 红线约束 | `src/main` 仅改动 `RecordLikeService.java` 一处方法体 + 常量 | 锁、分组、调度、配置零漂移 | 通过 |
| G9 token 基线 | PLAN.md `grep -cF` 29 项只增不减 | 29 项逐项实测 +1（追踪表逐行对齐） | 通过 |
| G10 tasks.json | `python -m json.tool` 语法校验 | 9 步 completed=true, 3 分组 passes=true, rc=0 | 通过 |
| G11 提交信息 | `-F` 文件无 BOM | BOM=False, UTF-8 LF | 通过 |
| G12 收口复检 | offline 450 不减，静态 811 不增，契约门无参 rc=0 | 亲跑全绿 | 通过 |

## 6. 受保护 token（PLAN.md 行命中数 `grep -cF`）

29 项：开工实测值 → C-02 追加后实测值。逐项 +1 成立，`TOKEN_VIOLATIONS=0`：

| Token | 开工实测值 | 收口实测值 | 变动 |
| --- | --- | --- | --- |
| 13.4 | 20 | 21 | +1 |
| 18.0 | 22 | 23 | +1 |
| 73.93 | 21 | 22 | +1 |
| 68.8 | 21 | 22 | +1 |
| 6315 | 18 | 19 | +1 |
| 1.8612 | 17 | 18 | +1 |
| 3.3066 | 17 | 18 | +1 |
| 5.7056 | 17 | 18 | +1 |
| 9.408 | 17 | 18 | +1 |
| 36525962432 | 17 | 18 | +1 |
| 36586847965 | 16 | 17 | +1 |
| 36438897772 | 17 | 18 | +1 |
| 36399582548 | 16 | 17 | +1 |
| 36098038547 | 16 | 17 | +1 |
| 2806 | 23 | 24 | +1 |
| 598 | 16 | 17 | +1 |
| 36736221648 | 15 | 16 | +1 |
| 36808102571 | 10 | 11 | +1 |
| 36821040708 | 8 | 9 | +1 |
| 36845152965 | 7 | 8 | +1 |
| 36871294588 | 7 | 8 | +1 |
| 36880083885 | 8 | 9 | +1 |
| 36958994260 | 8 | 9 | +1 |
| 36976873215 | 8 | 9 | +1 |
| 36992632143 | 7 | 8 | +1 |
| 36995450125 | 5 | 6 | +1 |
| 37008317295 | 6 | 7 | +1 |
| 37021305016 | 7 | 8 | +1 |
| 37591580687 | 6 | 7 | +1 |

## 7. 未覆盖项与不得推出的结论

1. **连接占用维度未覆盖（继承 Notice）**：Redisson 连接池在 pipeline 提交期间单连接独占时隙未做微秒级精细采样，属于继承 Notice。
2. **(b) 桶并发写窗口为 0 判别力（继承 Notice）**：由于对账持排他锁，并发写窗口内被锁拦截的尝试数为 0，结论基于并发控制类别，属于继承 Notice。
3. **CLIENT KILL 服务端硬中断不支持（继承 Notice）**：受控 Docker Redis 实例无权限执行硬断测试，中断残留收敛基于幂等覆盖理论与第二轮对账验证，属于继承 Notice。
4. **不得推出的结论**：不得据本机单实例耗时外推生产分布式集群的端到端吞吐收益；不得将耗时减少换算为百分比；生产调度参数与锁参数保持原有 10min / 60s 周期，本轮零改动。

## 8. 提交明细表

| 批次 | 完整 SHA | 提交主题 | 涉及文件 |
| --- | --- | --- | --- |
| C-01 | `3cce6c22f5aefb3ad4d4f34f3b85e35ac9b08af3` | feat(record): 对账 Redis 往返 pipeline 化分批提交（TASK-180） | 4 文件（`RecordLikeService.java`, `RecordLikeReconcilePipelineIT.java`, 报告 md, 数据 json） |
| C-02 | 父锚定：父 = `3cce6c22f5aefb3ad4d4f34f3b85e35ac9b08af3`（落库后 `git rev-list --left-right --count origin/main...main` = `0 3`） | docs(mailbox): 登记 TASK-180 实施验收与台账闭环（TASK-180） | 5 文件（`tasks.json`, `spec.md`, `handoff.md`, `PLAN.md`, `RecordLikeServiceTest.java`） |
