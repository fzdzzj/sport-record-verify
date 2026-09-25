# 复测：缩短提交事务 DB 足迹（TASK-139，同一负载 100 并发 × 2000 请求）

> 对应变更：`spec/changes/shorten-submit-db-footprint/`。只改数据库一类：提交事务少 1 条
> 不可见中间态 UPDATE + 热路径关闭 ShardingSphere SQL 展示。连接池默认 10、JVM、索引、
> innodb 刷盘均未动（任务书禁止叠加）。开工基线 `f5696fb8b70547e49b0ac6e2a5489e8fde8e10be`。

## 结论：有改善，幅度与插桩预测一致；就此停止叠加

| 指标 | 改前（TASK-138 head-c100） | 改后（本次 dbfoot-c100） | 变化 |
| --- | --- | --- | --- |
| QPS | 120.90 | **123.23** | +1.9% |
| P50 | 777.55ms | **711.45ms** | −8.5% |
| P95 | 1188.55ms | 1332.35ms | +12.1%（在噪声带内，见下） |
| 成功/限流/错误 | 2000 / 0 / 0 | 2000 / 0 / 0 | 错误率 0.00% |

- **判定为改善**：P50 −8.5% 与 QPS +1.9% 方向一致、可由机制解释（见 split 摘要）。
  P95 变差 12% 在同环境重复运行的噪声带内——行为完全未改的 split 跑（见下节）P95 就有
  1410ms，与改前基线 1188ms 相差 18%，故 P95 单项不作为反向证据。
- **按任务书停止叠加**：允许清单内改动已全部做完，无论改善大小都不得接着改池、JVM、
  索引或刷盘；本变更不开启后续优化。

## split 摘要（插桩拆段，行为未改，`docs/perf/data/attr-submit-tx-split.json`）

计时开、行为未改跑 `load 100 2000 split`：QPS 119.94 / P50 752.68ms（与基线 120.90 /
777.55 同噪声带，确认插桩本身不改变行为结论）。2000 个提交事务的分段 P50（微秒）：

| 段 | P50 | P95 | 说明 |
| --- | --- | --- | --- |
| select（幂等前置，含池排队等待） | 681,615us ≈ 681.6ms | 1276.2ms | **约 92% 是池等待**（池 10、并发 100），直接证实 TASK-138「9/10 是排队」的推断 |
| insertMain（主表 INSERT） | 2,859us ≈ 2.9ms | 10.4ms | |
| trackWrite（轨迹写入） | 22,551us ≈ 22.6ms | 57.4ms | 含 ShardingSphere 对 300 点多值 INSERT 的解析/路由与 sql-show 打印 |
| updateStatus（提交事务内 SUBMITTED→VERIFYING） | 2,083us ≈ 2.1ms | 8.1ms | **本变更删除的段**（占事务内工作 ≈4%） |
| commit（提交，含 redo 刷盘） | 28,861us ≈ 28.9ms | 66.4ms | 事务内最大段，fsync 地板，本变更不可触碰 |

事务内真实工作 P50 ≈ 56.5ms（不含池等待）。删掉 updateStatus 后 T_tx 降约 4%，
排队模型（P50 ≈ 9×T_tx + 自身）预测 P50 同比例小幅下降——与实测 −8.5% 同量级；
sql-show 关闭另削去 trackWrite 里的语句字符串打印份额。

## 复测口径自证

- 计时关闭：dbfoot 跑的 `logs/record.log` 中 `SUBMIT_TX_TIMING` 行数为 **0**
  （`record.submit.tx-timing-enabled=false` 默认未注入），插桩不污染对比。
- 环境未叠加：`MYSQL_POOL_SIZE` 未注入（池 10）、未叠加 `docker-compose.perf.yml`、
  未改 JVM 参数、未加索引、未改 innodb 刷盘。
- 提交事务 SQL：4 条 → **3 条**（select / insert 主表 / 轨迹写入；updateStatus 从提交
  路径删除，方法与回调/补偿路径保留）。
- 验收命令与产物：`bash scripts/perf/run-perf.sh load 100 2000 dbfoot`，原始数据
  `docs/perf/data/raw/dbfoot-c100-*`（gitignore，不入库）；Maven 门槛
  `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` 红转绿见 handoff。

## 剩余假设（未改善部分的一句话）

剩余 P50 ≈ 711ms 仍由池 10 的排队倍数（×约 9 前序事务）支配，事务内地板是 commit 刷盘
（P50 28.9ms）与轨迹多值 INSERT（P50 22.6ms）——若要再降，得缩轨迹写入/提交刷盘本身或
（按归因报告口径）在 SQL 次数与事务范围降下来之后才考虑调池/刷盘，属另立变更。

## 旧环境数字（不得与本表比较）

`docs/perf/压测报告.md`（2026-09-12）中的 136.8（137 QPS 口径）/ P95 1.60s / 541ms /
28~63ms 均为**旧环境**结果（含池 30 + 组合索引 + 1g 堆三项本快照未启用的优化），
与本表当前环境（池 10、默认 JVM、mapmatch 缺席）**不可直接比较**。
