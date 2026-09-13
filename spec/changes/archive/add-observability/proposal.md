# 提案：新增可观测性与监控告警（Prometheus + Grafana）

## Why

项目已完成 5 个功能模块与压测，但「生产化质感」还缺关键一环：服务只有 health/info 两个 Actuator 端点，**没有任何指标采集、可视化面板、告警**。「监控告警」目前只能讲设计（执行计划 P2 明确列为可选，进度超前来做）。落地 Prometheus + Grafana 后，项目的运维完整度大幅提升，也让压测报告（add-load-test-report）里的性能数据有了**持续观测的载体**，而不是一次性快照。

**背景**：
- 执行计划 W6「Sentinel 网关限流」已接入；P2「监控告警」原为可选。
- 骨架已用 `management` 暴露 health/info，但未接入 Micrometer Prometheus registry（whitelabel 默认只有基础指标）。
- docker-compose 已编排 Nacos/MySQL/Redis/RocketMQ，可直接追加 prometheus/grafana 两个 service。
- 压测报告已有 baseline/optimized 对比数据，监控面板可作为持续验证手段。

**当前状态**：各服务 `application.yml` 的 `management.endpoints.web.exposure.include` 仅 health,info；无 Prometheus exporter 依赖；无 prometheus.yml / grafana 配置；docker-compose 无监控栈。

**期望状态**：JVM（堆/GC/线程）、HTTP（QPS/P95/错误率）、数据源连接池、RocketMQ 消费位点、业务（校验判定分布）等指标经 `/actuator/prometheus` 暴露 → Prometheus 抓取 → Grafana 面板 + 告警规则（如 P95 超阈值、实例下线）。

## What Changes

- **依赖**：父工程 dependencyManagement 加 `micrometer-registry-prometheus`（版本由 Boot BOM 管理）；4 服务 + gateway 的 `management` 暴露 `prometheus` 端点并开启 `metrics`/`health`。
- **Prometheus**：新增 `prometheus/prometheus.yml`，静态配置抓取 5 个服务实例的 `/actuator/prometheus`；docker-compose 追加 `prometheus` service（端口 9090）。
- **Grafana**：新增 `grafana/`（datasource 指向 Prometheus + 预置 dashboard JSON），docker-compose 追加 `grafana` service（端口 3000，挂载 provisioning）。
- **告警规则**：`prometheus/alert-rules.yml`，覆盖「实例下线」「HTTP 错误率>阈值」「P95 延迟>200ms（对齐审批版 §8.2）」「JVM 堆使用率>80%」。
- **README/速览手册**：补监控栈访问方式、面板截图位、告警规则说明；ADR 增补监控选型说明（为什么 Prometheus+Grafana 而非自研/influx 等）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加可观测性能力域需求（ADDED）：指标暴露、采集、面板、告警。

### 受影响的代码
- 父 `pom.xml`（+micrometer-registry-prometheus）、4 服务 + gateway 的 `application.yml`（management 暴露）、`docker-compose.yml`（+prometheus/grafana）、`prometheus/`、`grafana/`

### 用户影响
- 无用户功能变更；运维/演示可观测性增强。

### API 变更
- 新增 `/actuator/prometheus` 端点（各服务），需注意端口暴露安全性（本地演示可直连）。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（README/速览/ADR）

## 时间线评估

较小：约 0.5-1 周（P2 扩展项）。

## 风险

- **指标端点暴露面** → 缓解：本地演示阶段直连 `/actuator/prometheus`；生产讲「内网抓取 + 网关不转发 actuator，最小权限」设计。
- **RocketMQ 消费位点指标需 broker 暴露** → 缓解：本提案先聚焦 JVM/HTTP/DB/业务指标，RocketMQ 指标讲设计或用 exporter 后续补。
- **内存预算**（本地 3-4GB）→ 缓解：prometheus/grafana 给 `JVM_XMX` 精简配置，必要时 disabled 由用户按需开启。
- **技术卡壳超时**（既定决策）→ 缓解：监控属 P2，若抢主线进度则降级为「Prometheus 抓取 + 最小 Grafana 面板」交付，告警规则可后补。