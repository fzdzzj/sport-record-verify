# 判别：对账 Redis 往返 pipeline 化语义边界（TASK-179）

## Why

TASK-178 E2 实测对账成本 96.3% 花在逐 record 三次 Redis 往返（SET 计数 + DEL 成员集 + SADD 重建，百万行档单轮 35.6s，仅 3% 在 DB 载入），且随 record 数线性增长——已证结构性瓶颈。候选优化「将逐 record 往返改为 pipeline 批量提交」可在不改变任何语义的情况下把往返次数从 3×records 压到常数级。但现有实现 [RecordLikeService.reconcileLikeCounts](../../../record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java) 的 DEL+SADD 与逐 record 提交存在两类已知窗口：①DEL 与 SADD 之间成员集短暂为空（并发 like 的 SADD 会被随后的 SADD 重建覆盖——现有行为）；②pipeline 将多 record 的写打包后，单 record 的可见时点从「逐条立即」变为「批次统一」（与 TASK-153「批末统一标记 SENT」同族的窗口扩大问题）。本任务**只判别语义边界，不实施优化**：若窗口扩大 ⇒ NO-GO；若语义等价 ⇒ GO 且产出同负载服务率量化，修复另立提案。

## What Changes

- 零生产改动：`reconcileLikeCounts` 及全部 `src/main` 冻结；判别以 test-only 受限切片对照「逐 record 基线 vs pipeline 候选」。
- J1 基线语义快照（真 Redis + scratch DB）：对账全程逐事件观测——计数/成员集的中间可见态序列、DEL→SADD 空窗时长、对账期间并发 like（SADD+INCR+RPUSH）的存活与被覆盖行为、锁防重语义。
- J2 pipeline 候选对照（同数据同装配）：Spring `executePipelined` 重放与基线等价的命令序列，逐事件观测同维度；差异逐条登记（可见时点、空窗形态、失败原子性、连接占用）。
- J3 判别式裁决（预注册）：六判据——(a) 最终收敛态等价（DB 权威覆盖不变）；(b) 对账期间并发写的丢失/覆盖行为不劣于基线；(c) 无新增失败模式（pipeline 部分失败 vs 逐条失败）；(d) 锁语义不变；(e) 命令序在连接上可重放；(f) 服务率量化（同数据对账耗时对比，登记不外推）。任一 (a)–(e) 劣化 ⇒ NO-GO。
- 产出限判别报告 + 机器摘要 JSON + test-only IT（`TASK179_IT_*`）+ 台账闭环。

## Impact

- 受影响代码：仅 test 与 docs；`src/main` 零改动。
- 判别结果为 GO 时：pipeline 化修复另立提案（含同负载验收与外部门槛）；NO-GO 时本方向终止，对账优化转向其他候选（如增量对账）。
- 证据边界：判别读数限本机隔离环境；GO 不等于生产收益承诺，服务率对比仅供提案参考，不写改善百分比。
