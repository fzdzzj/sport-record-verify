# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（可观测性能力域，全部为新增）。

## ADDED Requirements

### Requirement: 指标暴露
WHEN 任一服务运行,
系统 SHALL 经 `/actuator/prometheus` 暴露 Micrometer 指标，涵盖 JVM（堆/GC/线程）、HTTP（QPS/P95/错误率）、数据源连接池与业务判定指标。

#### Scenario: 端点可访问
GIVEN 服务已启动且依赖 micrometer-registry-prometheus 就绪
WHEN 请求 `/actuator/prometheus`
THEN 返回 Prometheus 文本格式指标
AND 包含 `jvm_` 与 `http_server_requests_` 前缀指标

#### Scenario: 端点未开启即不可达
GIVEN 服务未在 management 中暴露 prometheus 端点
WHEN 请求 `/actuator/prometheus`
THEN 返回 404 或隐藏
AND 不泄漏额外指标

### Requirement: 指标采集
WHEN Prometheus 运行,
系统 SHALL 按 prometheus.yml 静态配置抓取全部 5 个服务（gateway/user/record/verify + 未来 leaderboard）的指标端点。

#### Scenario: 抓取成功
GIVEN Prometheus 与服务均运行
WHEN 查看 Prometheus targets
THEN 各服务 target 状态为 UP
AND 指标带 instance 标签区分

#### Scenario: 实例下线可见
GIVEN 某服务停止
WHEN Prometheus 下一抓取周期
THEN 该 target 标记 DOWN
AND 触发对应告警

### Requirement: 可视化面板
WHEN 运维查看监控,
系统 SHALL 提供一个 Grafana Dashboard 展示核心指标：服务可用性、HTTP P95/错误率、JVM 堆/GC、连接池。

#### Scenario: 面板展示
GIVEN Grafana 已配置 Prometheus datasource
WHEN 打开预置 dashboard
THEN 展示服务可用性、延迟、错误率、JVM 面板
AND 数据来自 Prometheus

### Requirement: 告警规则
WHEN 指标越过阈值,
系统 SHALL 触发告警，至少覆盖：实例下线、HTTP 错误率超阈值、校验 P95 >200ms（对齐审批版 §8.2）、JVM 堆使用率 >80%。

#### Scenario: 延迟告警
GIVEN 校验接口 P95 超过 200ms 持续一段时间
WHEN Prometheus 评估告警规则
THEN 触发 P95 告警（firing 状态）

#### Scenario: 实例下线告警
GIVEN 某服务实例停止
WHEN Prometheus 检测 target DOWN
THEN 触发实例下线告警

---

## 备注

- 本变更为运维增强，不改变业务功能；指标口径对齐压测报告（add-load-test-report），形成「即时观测 + 历史实录」双层证据。
- /actuator/prometheus 本地演示直连；生产安全（网关不转发 actuator、内网抓取、最小权限）属于「讲设计」范畴。
- 监控栈选型（Prometheus+Grafana）理由随 ADR 记录。