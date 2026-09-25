# shorten-submit-db-footprint

## Why

TASK-138 在当前 HEAD 上用同一负载（100 并发 x 2000 请求）把 top_class 定为数据库，本变更只实施该类，并用同一负载验收。

已核实的事实（见 `docs/perf/归因-HEAD.md` 与 `docs/perf/data/attr-head-summary.json`，原始 CSV 在 gitignore 的 `docs/perf/data/raw/head-c100-*`）：

- 开工基线 `58cd104`，归因提交 `f5696fb`；load 一次成功：QPS **120.90** / P50 **777.55ms** / P95 **1188.55ms**，2000/2000 成功。
- record 连接池默认 10。排队模型自洽：理论上限约 10 / T_tx，反推 T_tx 约 80ms，与实测 120.90 QPS 接近 125。
- 提交路径同步阻塞 = 事务内 4 条 SQL + 提交刷盘 + 池排队；成功分支 0 次同步远程调用；GC 占墙钟约 0.5%；行锁 0ms。
- 观测 P50 里约 9/10 是池等待。等待倍数由单次连接占用 T_tx 决定，所以先缩短 T_tx，而不是先把池调大。

T_tx 的构成（4 条 SQL 各自耗时 / ShardingSphere 对 300 点多值 INSERT 的解析与日志 / 提交刷盘）在 TASK-138 没有按段实测，只有推断。本变更必须先插桩拆开，再只做允许清单里的缩短足迹改动。

不把连接池 10 调到 30 当作本轮改动：旧环境已经「批量插入 + 池 30」，吞吐停在约 137 QPS，当时判断下一天花板是 accept 队列与 MySQL fsync。在 SQL 次数和事务占用下降之前叠池，只是重复已知参数，且可能把并发刷盘打得更满。调用次数和事务范围没降之前，也不得调 JVM。

## What Changes

1. 默认关闭的提交事务分段计时（只为拆 T_tx，不是新的业务能力）：
   - 属性 `record.submit.tx-timing-enabled` 默认 `false`。
   - 为 true 时记录 select / insert 主表 / 轨迹写入 / 若仍存在的 updateStatus / commit 的耗时，并在日志中周期性打出可解析的 snapshot（样本数与各段 P50）。
   - 行为改动前开一次计时、用不同于 `head` 的 label 跑同一负载，写出 `docs/perf/data/attr-submit-tx-split.json`。
   - 行为改动后的验收跑必须关计时，避免插桩污染对比。
2. 提交路径少 1 条 SQL：`SportRecordService.submit` 直接 INSERT `status=VERIFYING, version=0`，删除同一事务里的 `updateStatus(SUBMITTED 到 VERIFYING)`。
   - 提交 HTTP 响应仍为 VERIFYING（现有测试已如此断言）。
   - afterCommit 仍异步发 SUBMITTED 事件；轨迹写入失败仍不得发事件。
   - `updateStatus` 与 SUBMITTED 到 VERIFYING 回调路径保留，供历史行/补偿使用。
   - 幂等（request_id 唯一键 + DuplicateKeyException）不改。
3. 热路径关闭 ShardingSphere SQL 展示：`record-service/src/main/resources/sharding.yaml` 的 `sql-show` 改为 `${SS_SQL_SHOW:false}`。开发排查可开环境变量；默认不得为 true。测试用 `src/test/resources/sharding.yaml` 不要求跟生产字面量一致。
4. 同一负载复测：`bash scripts/perf/run-perf.sh load 100 2000 dbfoot`，对比 TASK-138 的 120.90 / 777.55ms。指标没有改善则停止，不得在本变更里再改池、JVM、索引或 innodb 刷盘。
5. 规范差异：MODIFIED「轨迹提交幂等」首次提交的已提交状态；ADDED「提交事务不落不可见中间态」与「分片 SQL 展示默认关闭」。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md`：轨迹提交幂等（首次提交成功的已提交状态改为 VERIFYING）；校验状态机仍承认 SUBMITTED 为合法状态，只是提交路径不再单独提交它。

### 受影响的代码
- `record-service`：`SportRecordService.submit`、`application.properties`、`sharding.yaml`、`SportRecordServiceTest`、主配置 `sql-show` 默认值测试、可选计时辅助（不得新增依赖）。

### 用户影响
- 提交 API 响应本来就是 VERIFYING，无破坏性 API 变更。
- 新提交行的乐观锁 version 从「INSERT 0 再 UPDATE 成 1」变为「直接 0」。后续回调读行上 version，不依赖「提交后必为 1」。

### API 变更
- 无新端点，无破坏性 HTTP 契约。禁止为读计时新增业务 URL。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（`docs/perf` 复测摘要 + TASK-139 台账）

### 明确不做
- 不改 `MYSQL_POOL_SIZE` 默认 10，不叠加 `docker-compose.perf.yml`，不启用 innodb 刷盘 overlay。
- 不加滞留扫描组合索引，不碰 `spec/changes/add-verify-degrade-status-index/`。
- 不调 JVM/GC/堆，不开跨请求提交聚合（`add-perf-submit-aggregation-gate` 仍未开门）。
- 不改校验消费并发、outbox relay 批大小、好友榜、点赞、治理凭证。
- 不把「优化器使用了某索引」写成已变快。

## 时间线评估

中：先红后绿的单测 + 两次同一负载（拆分一次、验收一次）。压测失败只完整尝试一次，记未覆盖，不编造。

## 风险

- 若 T_tx 主要由 commit fsync 构成：少 1 条主键 UPDATE + 关掉 sql-show 可能几乎不动 QPS/P50。缓解：先写 split JSON；验收无改善则停止叠加，把 fsync 地板记入 handoff，交给指导侧另立变更。
- version 从 1 变为 0：只影响新插入行的乐观锁计数初值。缓解：回调/补偿一律读库上 version；单测覆盖提交后不再调用 `updateStatus`。
- sql-show 关闭后排障变难：环境变量 `SS_SQL_SHOW=true` 可临时打开。测试 yaml 覆盖不影响生产默认。