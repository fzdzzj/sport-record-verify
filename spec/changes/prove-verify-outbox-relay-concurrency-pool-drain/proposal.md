# 提案：判别批内并发 × 连接池对 outbox relay 排空斜率的影响（TASK-164，未定支）

## 1. Why

### 1.1 唯一问题
在 TASK-163 已落地的 `verify.outbox.relay-interval-ms=500` 之上，把批内并发 `relay-send-concurrency` 由 1 提到 4、并把 verify-service 的 Hikari `maximum-pool-size` 由默认 10 显式提到 20，负载停止后的排空斜率 slope（行/s）相对落地态基线提升是否 ≥1.5？只判别这一项组合，不叠加 batch-size、max-retry、interval、SQL、索引、MQ、JVM 任何其它参数。

### 1.2 三臂设计与预注册
- A（落地态基线）：`--verify.outbox.relay-diagnostics-enabled=true`（N=1，池默认 10）；
- B（只加池）：A ＋ `--spring.datasource.hikari.maximum-pool-size=20`；
- C（池＋并发）：B ＋ `--verify.outbox.relay-send-concurrency=4`。
- 交错次序 A1→B1→C1→A2→B2→C2（6 计数轮）＋预热轮 ≤4（全弃）；替换轮 ≤2 且仅限 M4/M6 红；估计量与 TASK-163 相同：`slope = P_peak / ((t_zero − t_peak)/1000)`，P_peak ≥100。
- 预注册判决门 M2：`slope(C1)/max(slope(A1),slope(B1)) ≥ 1.5` 且 `slope(C2)/max(slope(A2),slope(B2)) ≥ 1.5`；结构预测区间 [1.7, 2.5] 依赖 TASK-161 明写「未被回答」的 S_prod(N) 可迁移性假设，只作先验、不是门。

### 1.3 实测结果（未定支）
6 个计数轮全部有效（逐轮 M1/M4/M5/M6 过），M3 排序控制门 10.33% ≤20% 通过、C 臂重复性 21.20% ≤30% 通过；但 **M2 两比值 = 1.3644 与 1.2229，均未达 1.5**（又均 >1.0，反证支不成立）⇒ 按预注册三支规则归**未定支**。同臂极差（A 10.3%、B 17.5%、C 21.2%）与估计量采样粒度（每排空窗仅 6~12 样本）同量级，本轮无法区分「真实效应低于门槛」与「噪声掩盖达标」，也不允许用加跑凑结论。

## 2. What Changes

1. **报告与数据（本轮全部落地物）**：`docs/perf/判别-outbox-relay-并发与池-排空斜率.md` 与机器摘要 `docs/perf/data/exp-outbox-relay-concurrency-pool-drain.json`。
2. **不改动任何配置与生产代码**：`application.yml` 零改动（未落地 `maximum-pool-size: 20` 与 `relay-send-concurrency: 4`）、未新增测试类、未跑确认轮 D（落地支未触发）；`relay-send-concurrency` 生产默认值仍为 1、`maximum-pool-size` 仍为默认 10。
3. **登记方法论**：本判别的三臂协议、门槛与未定支处置作为 ADDED 需求入 delta，供后续（如更细采样粒度或更高 C 取值）复判时引用。

## 3. Impact

- **数据面**：C 臂（池 20＋并发 4）排空斜率 121.9729/98.5905 行/s，对同对基线 max(A,B) 的比值为 1.3644/1.2229——方向一致为正、未达预注册 1.5 门槛，归未定支；任何「收益 36%/22% 已证明」或「并发无收益」的表述都不被本数据支持。
- **语义与资源面**：六轮 M4 全过（cohort=markSent=SENT 增量=2010、零重试、零耗尽、零重复、零 RECONSUME_LATER）；M6 资源门全过（连接超时增量 0、消费侧零饿死）——即该组合在本演示环境**安全但收益未证**。
- **代价披露**：C 臂诊断日志 residualMs 为负（线程时间聚合口径，非未归因墙钟）；并发下 Com_select 总量与串行同量级（C/A 均值比 1.0002）。
- **口径冲突披露**：任务书 M5 的 Com_update 字面常数（=outbox 行数 2010）与其历史基线（TASK-163 留档同为 4020=2×2010：判定回写＋markSent 每行两条既有 UPDATE）矛盾；本轮按确定性基线 4020 执行该门并全程披露，提请指导侧裁决。

## 4. 停止条件（立即停、如实报、不落地）

起栈失败或容器起不来 / 健康检查不过 / Nacos 存在覆盖 / java 进程异常且停不掉 / 磁盘 Free <100 GB / 演示库耗尽行增长 / 负载 429 或 errors>0 / relay 失败或耗现行 / drain.csv 缺失断裂 / M0 连接余量 <30。本轮均未触发。M2 不过不是停止条件，是裁决条件（未定支）。
