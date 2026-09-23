# narrow-actuator-exposure

## Why

findings F08：网关白名单含 `/actuator/**`（application.yml:114），主规格「白名单收紧」与
「网关统一鉴权·白名单放行」场景把该整段通配写进了需求文本，导致 actuator 全部子路径
（metrics/env/prometheus/heapdump 等任意未来端点）经网关裸放行；六个服务
`show-details: always` 使 health 响应泄出组件明细（数据源、Redis、磁盘等）。
监控栈按 ADR-0007 走内网直连（prometheus.yml 六个 target 均为 host.docker.internal 直连服务端口），
网关入口只需要放健康探针。

## What Changes

- 网关白名单 `/actuator/**` → `/actuator/health`（精确匹配，无 /** 后缀）；
- 六个服务 `management.endpoint.health.show-details: always` → `never`（health 仍返回 UP/status）；
- 不动各服务 include 列表（prometheus/metrics 端点本体保留）、不动监控栈编排、不动网关路由。

## Impact

- 网关安全边界：actuator 指标/环境端点回到鉴权分支（无 token → 401/1001）；
- 监控栈不受影响（内网直连不经网关）；health 探针脚本（scripts/perf）只打 /actuator/health，不受影响。
