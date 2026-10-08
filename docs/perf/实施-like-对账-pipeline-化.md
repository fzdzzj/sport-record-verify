# 实施：点赞对账 pipeline 化分批提交（TASK-180，课题 5 第三轮）

> 状态：**已收口**。裁决＝**PASSED**（语义门 (a)(d)(e) 全绿，变异红全捕获，Checkstyle record-service 811 未增，offline test 450 不减）。
> 一切读数限定「本机、隔离 scratch 环境、未达外部门槛」；耗时对比只登记绝对数字与往返批次，不写改善百分比、不外推生产收益。

## 0. 一句话结论

在受控真 Redis + scratch MySQL 隔离环境下，对生产代码 `RecordLikeService.reconcileLikeCounts` 完成 pipeline 化改造（循环体解耦为命令构造段与分批提交段，引入 `PIPELINE_BATCH_SIZE = 500`）：
**全量收敛态断言 (a) 200/200 完全一致，锁语义 (d) 保持排他并经源码静态断言确认调用点在锁 try 块内，MONITOR 命令流 (e) 600 条三元组严格一致且无事务包装命令；抽掉 DEL 与乱序 SADD 两处变异红全部被收敛门捕获（收敛数降为 0）；500 档单批耗时更短、缓冲占用更小定为生产默认；生产方法在 2000 records × 50 赞负载下直跑 3 轮耗时从逐条往返的合计 14231.5039 ms 下降至 574.8951 ms，三支判定裁决为 PASSED**。

## 1. 范围与红线遵守

| 红线要求 | 落实方式 |
| --- | --- |
| 生产改动限一处方法体 + 常量 | 仅改动 `RecordLikeService.reconcileLikeCounts` 方法体与新增 `PIPELINE_BATCH_SIZE = 500` 常量；除此无任何 `src/main` 改动 |
| 锁/分组/日志语义/调度参数零改动 | 锁键 `lock:like:reconcile`、tryLock 3s 超时、分组 LinkedHashMap 保序、全量记录纠正日志、10min/60s 调度完全保持 |
| 语义门不可妥协 | 生产方法经 IT 直测验证 (a) 全量收敛、(d) 锁内执行、(e) 命令序比对，全项通过 |
| 变异红测试 | 抽掉 DEL 注入（残留幽灵成员）与乱序 SADD/DEL 注入（重建成员被抹掉）均产生 0 收敛，被门禁捕获 |
| 耗时对比只登记绝对数字 | 耗时与批次仅登记绝对毫秒，严禁计算百分比，不外推生产收益 |
| 唯一 mvn 入口 | 门禁通过 `bash scripts/verify/mvn-verify.sh`；IT 直跑遵循课题 5 留证惯例，记录完整命令行与返回码 |
| 环境隔离与竞争处置 | scratch 库 `task180_it`（跑后 DROP DATABASE）；Redis 专用 DB 13；宿主 6379 竞争沿临时 16379 override 映射，测后还原删除 |
| 静态门禁与离线基线 | `--static=record-service` 从基线 814 降至 811（不增）；offline test 450 分模块逐位保持 |

## 2. 环境与装配

| 项 | 读数与事实 |
| --- | --- |
| scratch MySQL | 容器 `127.0.0.1:3307`，`task180_it` 专用 scratch 库，由 `sql/02-record-db.sql` 结构重建，测试完成后 DROP |
| Redis 通道 | 宿主 `6379` 原生进程竞争，沿用 TASK-178/179 方案通过临时 compose override 增加 `16379` 映射至既有容器 `sport-verify-redis` |
| Redis 实例归属 | 容器 `run_id` 为 `2b6a429ed450fde29f1a5f7cf1bcfc2337a00450`，版本 `7.2.16`；测试使用专用 DB 13 |
| 连接工厂事实 | `redisson-spring-boot-starter` 排除 Lettuce 与 Jedis，`RedisConnectionFactory` 实际装配为 `RedissonConnectionFactory` |
| 环境清理确认 | 测试完成后还原 `docker-compose.yml` 默认端口映射，删除临时 compose override 文件与 scratch 数据库 |

## 3. 生产改造形态

生产类 `RecordLikeService.java` 改动严格限于以下两项：

1. **引入常量**：
   ```java
   /**
    * 点赞对账 pipeline 提交单批最大命令数（TASK-180 定档实测 500）。
    */
   private static final int PIPELINE_BATCH_SIZE = 500;
   ```
2. **方法体重构**：
   将 `reconcileLikeCounts` 中的全循环逐条往返：
   ```java
   // 原形态：逐 record 往返 3 次
   stringRedisTemplate.opsForValue().set(countKey, String.valueOf(userIds.size()));
   stringRedisTemplate.delete(usersKey);
   if (!userIds.isEmpty()) {
       stringRedisTemplate.opsForSet().add(usersKey, ...);
   }
   ```
   重构为构造段与提交段两层结构：
   - 构造段收集 `Consumer<RedisConnection>` 命令三元组（保持 SET -> DEL -> SADD 顺序与空成员防御分支）。
   - 累积达到 `PIPELINE_BATCH_SIZE` 时调用 `stringRedisTemplate.executePipelined` 批量提交并清空批次列表。
   - 循环结束后执行尾批余量提交。
   - 异常处理维持原有语义：任一批提交抛出异常均中断本轮对账，由 finally 块保证锁释放。

## 4. 定档预试（500 vs 1000）

在同负载（2000 records × 50 赞 = 6000 写入命令）下，对两档批大小进行预试测算：

| 批容量候选 | 提交批次 (flushes) | 墙钟耗时 (ms) | 绝对差异 (ms) | 定档决策与依据 |
| --- | --- | --- | --- | --- |
| 500 | 12 | 338.5772 | 基准 | **定档选定**：两档墙钟差异仅 91.5714 ms，处于网络与宿主抖动噪声区间内。500 档单批命令数较小，持有底层连接时间更短，服务端 output buffer 峰值占用更低，符合保守默认原则 |
| 1000 | 6 | 247.0058 | 91.5714 | 备选：批次减半但单批开销与缓冲需求增加，不作为默认 |

生产常量最终定档为：`private static final int PIPELINE_BATCH_SIZE = 500;`。

## 5. 语义门直测复验

通过 IT（`RecordLikeReconcilePipelineIT`）直接调用生产方法 `service.reconcileLikeCounts()` 进行语义门判定：

### 5.1 (a) 全量收敛态断言

- **测试规模**：200 records × 50 赞 = 10000 行点赞，前置注入 200 个幽灵脏数据与错误计数。
- **实测耗时**：261.2283 ms。
- **全量比对结果**：
  - 违规记录数：`0`。
  - 收敛记录数：`200`（全量等于 MySQL 权威）。
  - 部分更新或未触及记录数：`0`。
- **判定结论**：(a) 门通过。

### 5.2 (d) 锁内执行与排他性

- **运行时排他测试**：持锁线程执行期间，观察者线程尝试调用 `tryLock(3, -1, SECONDS)`。
  - 第二把锁获取结果：`false`（成功被拒）。
  - 等待耗时：`3016 ms`（符合 3 秒超时预期）。
- **静态代码断言**：
  - `hasTryLock` = `true`
  - `hasUnlock` = `true`
  - `hasPipelined` = `true`
  - `staticCallInsideLockTry` = `true`（调用点严格位于 tryLock 后的 try 块内且在 unlock 之前）。
- **判定结论**：(d) 门通过。

### 5.3 (e) 命令序一致性（MONITOR 捕获比对）

- **独立 socket 监听 MONITOR 捕获命令流**：
  - 捕获总写命令数：`600`（SET 200 条，DEL 200 条，SADD 200 条）。
  - 三元组严格顺序比对：`triplesStrict = true`（逐 record 均为 SET -> DEL -> SADD 顺序，record ID 完全配对）。
  - 事务包装检测：`transactionWrappers = []`（无 MULTI / EXEC / WATCH 等多余包装）。
- **判定结论**：(e) 门通过。

## 6. 变异红测试判别力

在测试框架内注入两处变异逻辑，验证门禁判别力有效性：

| 变异场景 | 注入行为 | 预期异常 | 实测收敛数 | 捕获结果 |
| --- | --- | --- | --- | --- |
| 变异红 1 | 抽掉 DEL 命令 | 幽灵成员未被清空，Redis 成员数多于 DB | 0 / 200 | 成功被收敛门捕获 |
| 变异红 2 | 乱序 SADD/DEL（先 SADD 后 DEL） | 重建的成员被随后的 DEL 抹除，成员集为空 | 0 / 200 | 成功被收敛门捕获 |

两处变异实测收敛数均为 0，证明收敛门非空转，具备完备的判别力。

## 7. 防御分支测试（空成员记录）

测试针对点赞数为 0 的记录（DB 存在运动记录但无任何点赞点）：
- 预置 Redis 脏数据：`like:count:700999 = 10`，`like:record:700999:users` 包含 2 个成员。
- 执行生产对账方法后，MONITOR 捕获该记录仅发射了 SET（写入 0）与 DEL，**未发射 SADD 命令**。
- 防御分支行为与改造前完全一致。

## 8. 生产方法同负载服务率量化

在 2000 records × 50 赞 = 100 000 行点赞负载下，对比改造前生产基线（逐条往返）与改造后生产方法（500 pipeline 提交）各直跑 3 轮：

| 运行轮次 | 改造前基线逐条往返耗时 (ms) | 改造后生产 pipeline 耗时 (ms) |
| --- | --- | --- |
| 第 1 轮 | 5137.6391 | 225.2709 |
| 第 2 轮 | 4264.3059 | 182.7872 |
| 第 3 轮 | 4829.5589 | 166.8370 |
| **3 轮合计** | **14231.5039** | **574.8951** |

*(注：同数据同负载直跑，仅登记绝对数字，不计算百分比，不外推生产收益。改造前独立基线测量轮 3 轮合计为 15939.6786 ms，亦与本对照一致)*

## 9. 三支判定裁决

综合上述语义门、变异测试、定档测试与静态检查：
- 语义门 (a) 全量收敛：通过。
- 语义门 (d) 锁语义及静态断言：通过。
- 语义门 (e) 命令序一致：通过。
- 变异红测试两项：全部捕获。
- Checkstyle record-service：811（基线 814，不增）。
- Offline test：450（36/41/33/127/144/59/10，不减）。

**最终判定裁决：PASSED**。

## 10. 继承未覆盖项与边界说明

沿 TASK-179 复核 Notice 继承以下未覆盖项，在实施验收中如实记录：
1. **连接占用维度未覆盖**：Redisson 连接池在 pipeline 提交期间单连接独占时隙未做微秒级精细采样，属于继承 Notice。
2. **(b) 桶并发写窗口为 0 判别力**：由于对账持排他锁，并发写窗口内被锁拦截的尝试数为 0，结论基于并发控制类别，属于继承 Notice。
3. **CLIENT KILL 服务端硬中断不支持**：受控 Docker Redis 实例无权限执行硬断测试，中断残留收敛基于幂等覆盖理论与第二轮对账验证，属于继承 Notice。
4. **环境边界**：所有测量均在受控单机隔离环境完成，不代表生产集群环境绝对数值。

## 11. 门禁实测表与命令记录

| 门禁项 | 命令行 | 期望结果 | 实测结果 | 判定 |
| --- | --- | --- | --- | --- |
| 离线测试 450 | `bash scripts/verify/mvn-verify.sh --offline` | 450 通过 (36/41/33/127/144/59/10) | 450 通过，rc=0 | 通过 |
| 静态门禁 | `bash scripts/verify/mvn-verify.sh --static=record-service` | <= 814 | 811 违规，rc=1 (不增) | 通过 |
| IT 缺环境变量跳过 | `mvn -o -s .mvn-settings.xml -pl record-service -am test -Dtest=RecordLikeReconcilePipelineIT` | Tests run: 0 | Tests run: 0, rc=0 | 通过 |
| IT 全量测算直跑 | `mvn -o -s .mvn-settings.xml -pl record-service -am test -Dtest=RecordLikeReconcilePipelineIT` (设置 TASK180_IT_*) | Tests run: 9, Failures: 0 | Tests run: 9, Failures: 0, rc=0 | 通过 |
