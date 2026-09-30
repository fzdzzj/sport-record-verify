## ADDED Requirements

### Requirement: 批内并发与连接池组合的 relay 排空能力判别须以三臂交错协议与预注册 M2 门裁决
WHEN 决策需要评估 `verify.outbox.relay-send-concurrency` 与 `spring.datasource.hikari.maximum-pool-size` 组合（在 `relay-interval-ms=500` 落地态之上）对负载停止后排空斜率的影响,
判别作业 SHALL 在同一 jar 上执行三臂交错计数轮 A1→B1→C1→A2→B2→C2（A=落地态基线仅开诊断、B=A 加池 20、C=B 加批内并发 4；唯一差异＝注入参数），以预注册估计量 `slope = P_peak / ((t_zero - t_peak)/1000)`（要求 P_peak ≥ 100）为核心度量，并以 M2 效应门（`slope(C1)/max(slope(A1),slope(B1)) ≥ 1.5` 且 `slope(C2)/max(slope(A2),slope(B2)) ≥ 1.5`）作为落地与否的判决门。

#### Scenario: 三臂机制与逐轮有效性
GIVEN 三臂共享同一诊断开关且每臂切换重启 verify-service、全程同一 jar
WHEN 执行计数轮与排空采样
THEN 作业 SHALL 逐轮验证 M1 机制门（A/B 臂零建池日志且批次诊断行 sendConcurrency=1、C 臂恰一条 sendConcurrency=4 建池日志且批次行 sendConcurrency=4、C 臂 residualMs 按线程时间聚合口径披露）、M4 健康与语义门（cohort=markSent=SENT 增量、零重试/零耗尽/零重复/零 RECONSUME_LATER/零锁异常）、M5 代价门（Com_select C/A 增量比 ≤1.5、Com_update 增量精确等于该轮确定性基线）与 M6 并发专属资源门（连接超时增量=0、pending/active 峰值披露、消费侧零饿死）
AND SHALL 逐轮披露提交 QPS 与宿主共变量（不设门），并以 M3 排序控制门 `|slope(A2)-slope(A1)|/mean(slope(A1),slope(A2)) ≤ 20%` 排除系统漂移解释、以 C 臂重复性 ≤30% 约束可落地性

#### Scenario: M2 未达线时归未定支且不得落地
GIVEN 六个计数轮全部有效且 M3 与重复性通过、但 M2 任一比值 < 1.5（且两比值不全 ≤1.0、无 M4/M6 红）
WHEN 形成三支裁决
THEN 作业 SHALL 判归未定支，只报数字与估计量方差（含同臂极差与采样粒度披露），SHALL NOT 修改 `application.yml`、SHALL NOT 新增默认值绑定测试、SHALL NOT 跑确认轮 D，SHALL NOT 以加跑凑结论
AND 落地支持条件 SHALL 保持为 M2 两比值 ≥1.5 且 M3 ≤20% 且 C 臂重复性 ≤30% 且逐轮 M1/M4/M5/M6 全过，缺一即回未定支
