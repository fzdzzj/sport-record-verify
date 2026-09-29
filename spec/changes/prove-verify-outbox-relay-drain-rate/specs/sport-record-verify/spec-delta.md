## ADDED Requirements

### Requirement: relay 调度间隔净投递能力须以负载停止后的排空斜率及排序控制门裁决
WHEN 决策需要评估 `relay-send-concurrency`、`batch-size`、`max-retry` 等参数不变时，`verify.outbox.relay-interval-ms` 由 5000 改为 500 对 relay 净投递能力的影响,
判别作业 SHALL 在同一 jar 上执行受控单因素交错计数轮 A1→B1→A2→B2（A=无注入默认 5000 / B=命令行注入 500），以负载停止后测得的排空斜率（rows/s）作为核心度量，并引入时间排序控制门 M3（要求 A2 与 A1 斜率相差 ≤20%）以排除系统预热干扰。

#### Scenario: 排空斜率预注册估计量与交错协议
GIVEN 唯一变量为 `verify.outbox.relay-interval-ms`，预算硬上限 4 计数轮 + 最多 2 替换轮（仅 M4 健康门红允许替换）+ 最多 4 预热轮
WHEN 执行计数轮与排空采样
THEN 作业 SHALL 在每轮负载完成后立即以 2s 间隔采样 PENDING 数量并打时间戳，写入 `raw/task163-<label>-drain.csv` 直至归零
AND SHALL 按预注册估计量 `slope = P_peak / ((t_zero - t_peak)/1000)` 计算净投递斜率，要求 `P_peak >= 100` 并披露前半段斜率 `slope_half` 作线性度检查
AND SHALL NOT 换 B 档取值、SHALL NOT 叠加并发/批次/连接池作为补救

#### Scenario: M3 排序控制与因果判据
GIVEN A2 轮次在时间上晚于 B1 与 B2
WHEN 评估单因素有效性与因果归属
THEN 作业 SHALL 检验 `|slope(A2) - slope(A1)| / slope(A1) <= 0.20` 且 A2 空档机制门 M1 成立
AND 若 A2 斜率相较 A1 显著提升（>20%）足以说明「越跑越快」，则作业 SHALL 判定系统时间漂移无法排除，归入反证支且不落地

### Requirement: 落地支成立时调度间隔默认值变更须经 C 轮确认且结论不得外推
WHEN 判别得出四计数轮全部有效（M1、M4 通过）且 M2 效应门（两个比值 ≥1.5）、M3 排序控制门、M5 代价门全过（**落地支**）,
作业 SHALL 以纯新增方式把 `verify.outbox.relay-interval-ms: 500` 写入 verify-service 的 classpath YAML（numstat `N/0`、`^verify:` 根键保持恰好 1 个）并新增纯 JUnit 绑定测试类（无 `@SpringBootTest`、无中间件与网络依赖），随后在无任何命令行注入下执行 C 确认轮。

#### Scenario: C 确认轮判据与回滚保护
GIVEN 新 jar 已在 classpath application.yml 落地默认值 500
WHEN 启动新 jar（无任何命令行注入）执行 C 确认轮
THEN 作业 SHALL 验证 C 轮满足 B 档机制门特征（>1s 空档 ≤5）且 `slope(C) / mean(slope(A1), slope(A2)) >= 1.5`
AND 若 C 轮判据任一项未过，作业 SHALL 立即按编辑前备份字节级回滚 `application.yml`（`cmp` rc=0）、删除新测试类并复跑 offline 验证计数恢复 120

#### Scenario: 结论适用边界与代价披露
GIVEN 判别在四服务局部栈、单实例、每轮 2000 行、R5 降级路径下完成
WHEN 形成报告与对外结论
THEN 作业 SHALL 限定结论于该装配与观测窗，SHALL NOT 声称全链路收益，SHALL NOT 声称任何并发收益（`relay-send-concurrency` 全程保持 1）
AND SHALL NOT 将 slope 换算为 P50 延迟改善，SHALL 将空扫 tick 频率提升约 10 倍与 `Com_select` 增量作为已披露代价并列呈现
