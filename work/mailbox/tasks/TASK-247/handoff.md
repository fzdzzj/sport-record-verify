# TASK-247 台账（handoff）

> **TASK**：E5 生产部署姿态演练——CORS 动态多域名注入、跨域 Cookie 凭证透传与 TLS 终止协同演练（候选 H2，MEDIUM 档）  
> **开工基线**：`e3ed868`（main，工作树干净）  
> **方案**：候选 H2（生产部署姿态演练）  
> **状态**：已实施并门禁全绿

## 1. 实施摘要

| 判别式 | 归属 | 状态 | 说明 |
| --- | --- | --- | --- |
| R1 CORS 生产多域名动态注入 | gateway-service | 已完成 | 保留 `${CORS_ALLOWED_ORIGINS:...}` 动态注入；真实网关上下文断言多域名预检回显 ACAO + ACAC:true、恶意 Origin 无 CORS 头 |
| R2 TLS 终止与协议感知 | gateway-service | 已完成 | `server.forward-headers-strategy: framework` + X-Forwarded-* 协议识别单测与配置断言 |
| R3 跨域 Cookie 凭证透传 | gateway-service | 已完成 | Cookie: refresh_token 经网关全局过滤链（RequestId → Auth）下游请求头无损保留断言 |
| R4 Compose 环境变量与文档对齐 | compose + docs | 已完成 | compose.services.yml 注入占位、env.example 补说明、README 补反向代理协同部署章节 |

## 2. 修改清单（实际改动集）

> 与实际改动集一致（git diff e3ed868 + 未跟踪），恰为 spec.md §2 的 7 文件白名单，无任何超出项。

- gateway-service/src/main/resources/application.yml
- docker-compose.services.yml
- scripts/verify/env.example
- gateway-service/src/test/java/com/sportverify/gateway/GatewayProductionPostureTest.java
- README.md
- work/mailbox/tasks/TASK-247/spec.md
- work/mailbox/tasks/TASK-247/handoff.md

> 零触碰红线遵守：`record-service`、`verify-service`、`user-service`、`leaderboard-service`、
> 根与各子模块 `pom.xml`、`web/package.json`（零新外部依赖）、`work/mailbox/PLAN.md` 均未改动；
> 未执行 `git push`、未发起 PR。

## 3. 门禁验证记录

- 后端单测：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl gateway-service -am` → BUILD SUCCESS（全绿，含新 GatewayProductionPostureTest）✓
- 前端类型：`cd web && pnpm run type-check` → 0 错误 ✓
- 前端单测：`cd web && pnpm test` → 全绿 ✓
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-247 --baseline=e3ed868` → 退出码 0，判据 A 两件套齐 + 判据 B 清单一致，契约校验通过 ✓

## 4. 已知受限与偏差声明

- **R1 预检短路口径**：CORS 多域名断言走真实网关上下文 + HTTP 预检 OPTIONS（CorsWebFilter 短路响应，
  无需可路由下游，离线稳定）；未白名单的恶意 Origin 预检断言「不返回 `Access-Control-Allow-Origin` 头」
  （不否定其 fallthrough 到路由/404 的行为，后者的异常包装见 TASK-246 B24）。
- **R2 协议感知测试方式**：`forward-headers-strategy: framework` 属服务端入站适配，测试以
  `ForwardedHeaderFilter`（框架策略等价适配器）直驱断言 X-Forwarded-Proto/Host/Port → scheme 转 https，
  并以资源文件断言 application.yml 已声明 framework；未在真实路由下游观测（无后端可观测目标）。
- **R3 过滤链口径**：跨域 Cookie 透传以网关真实全局过滤器（RequestIdGlobalFilter → AuthGlobalFilter
  白名单透传分支）串联断言下游请求头完整保留 Cookie，未把 `lb://` 路由真实负载均衡纳入断言（离线无 Nacos）。

## 5. 双提交记录

- **C-01 实施提交**：`feat(gateway): 落实生产部署姿态演练与CORS/TLS/Cookie协同治理（TASK-247）`（文件 1–5）
- **C-02 台账提交**：`docs(mailbox): 登记TASK-247实施完成与门禁回填（TASK-247）`（文件 6–7）