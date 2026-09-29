## ADDED Requirements

### Requirement: relay 调度间隔收益须在预热吸收冷启动的可比性口径下以预注册门槛裁决
WHEN 决策需要知道 `relay-send-concurrency`、`batch-size`、`max-retry`、消费线程、池、JVM、MQ 均不变时，`verify.outbox.relay-interval-ms` 由 5000 改为 500 的 `callback→SENT` 延迟收益是否**可重复**,
判别作业 SHALL 在同一 jar（A/B 计数轮之间不重建）上跑单因素 A-B-B-A 计数轮（A=无注入默认 5000 / B=命令行注入 500），把全局首轮 W0 与每次 verify-service 重启后的 W_cell 全部作为**丢弃预热轮**吸收冷启动，并以预注册 V1–V7 逐轮判定有效性、以改善门/资源门/语义门与三支裁决裁决，不得事后放宽、不得换 B 档取值再试。

#### Scenario: 受控单因素轮次协议与预算
GIVEN 唯一变量为 `verify.outbox.relay-interval-ms`，预算硬上限 6 计数轮 + 4 预热轮，失败轮照占预算
WHEN 执行计数轮 A-B-B-A（替换轮仅追加尾部）
THEN 作业 SHALL 逐轮记录 label/cell/注入/QPS/ok/errors/429/消费失败/重投/P50/P95/P99/n/净投递速率/空档/排空耗时与 V1–V7 结论，预热轮全部丢弃（不进统计、不进中位数）但逐轮留档
AND SHALL NOT 在任一 cell 预算耗尽仍不足 2 个有效轮时加跑，SHALL NOT 换档（不试 200/1000/2000 ms）、SHALL NOT 叠加 batch/并发/池/JVM/SQL/索引/MQ 作为补救

#### Scenario: 自指可比性门槛的多读法披露
GIVEN V6「提交 QPS 相对全部有效计数轮（A+B 合并）中位数偏差 ±15%」在定义上自指（有效集依赖中位数、中位数依赖有效集）
WHEN 形成可比性结论
THEN 作业 SHALL 枚举全部可辩护读法（预注册基准池 / 全量一次性中位 / 最大自洽不动点 / 到达序滚动池）并逐读法给出有效轮集合与分支结论，不得单方选择最宽松读法
AND 仅当各读法收敛同一裁决时，方可直接据此落支

### Requirement: 调度间隔默认值仅可在落地支成立时变更且结论不得外推
WHEN 判别得出裁决,
作业 SHALL 仅在 A cell ≥2 有效轮、B cell ≥2 有效轮、改善门成立且资源门与语义门全过（**落地支**）时，才以纯新增方式把 `relay-interval-ms: 500` 写入 verify-service 的 classpath YAML（numstat `N/0`、`^verify:` 根键仍恰好 1 个）并新增纯 JUnit 绑定测试类（无 `@SpringBootTest`、无中间件/网络依赖），随后跑 C 确认轮（`>1s` 空档 ≤5 且中位 ≤2000 ms 且 C 轮 P50 落两轮有效 B 中位 ±25% 内），C 不过则按编辑前备份字节回滚（`cmp` rc=0）并复跑 offline 证明计数回到 120。

#### Scenario: 未定/反证支的边界
GIVEN 任一 cell 有效轮 <2、或 V6/V7 可比性不成立、或改善门未达、或资源/语义门有红
WHEN 形成裁决
THEN 作业 SHALL 记「未定/不推荐」并逐条列出触发的门与实测读数
AND SHALL NOT 修改 `application.yml`、SHALL NOT 新增测试类、SHALL NOT 产生 C 轮或回滚动作，SHALL NOT 翻案 TASK-144 的 UNDETERMINED 或改写其任何数字

#### Scenario: 结论不得外推
GIVEN 判别在四服务局部栈（leaderboard/mapmatch/postgis 未起、R5 降级路径）、单实例、每轮 2010 行、有限观测窗内完成
WHEN 形成报告与裁决措辞
THEN 作业 SHALL 限定结论于该装配与该窗口，SHALL NOT 声称并发收益（`relay-send-concurrency` 全程 = 1），SHALL NOT 把本轮 P50 与 TASK-152 的 18.0 ms/行或 TASK-156/161 的 S(N)/S_prod(N) 并列成「优化前后」
AND SHALL 把「下调 interval 使空扫 tick 频率约 10×」作为已披露代价与收益并列登记（`Com_select` 增量倍数缺失即视为披露不完整）
