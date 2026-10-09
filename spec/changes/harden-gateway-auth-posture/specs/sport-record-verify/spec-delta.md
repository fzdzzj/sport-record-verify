# spec-delta：strict 联动强制鉴权与网关 health 明细收口（TASK-191 harden-gateway-auth-posture）

## MODIFIED 需求：网关生产姿态硬约束（app.security.strict 联动鉴权开关）

### 变更前

`app.security.strict=true` 仅校验治理凭证（app.governance.token 缺失/回落演示默认即启动失败，TASK-126）；`app.auth.enabled` 独立于 strict——生产部署忘记开启鉴权时无启动期拦截，业务面裸透传（仅治理路径失败关闭）。

### 变更后

`app.security.strict=true` 且 `app.auth.enabled=false` → AuthGlobalFilter.init() 抛 IllegalStateException，网关拒绝启动（身份边界与密钥完整性同列生产姿态硬约束）；检查顺序置于治理凭证校验之前（首个报错指向身份边界）。`strict=false`（默认）行为零变化——本地演示/压测口径（auth.enabled=false 兼容压测脚本）完全保留。

## MODIFIED 需求：网关 actuator health 组件明细（F08 残留收口）

### 变更前

gateway application.yml `management.endpoint.health.show-details: always`——其余 5 个后端服务均为 never（TASK-125 口径），网关 health 在白名单内免 token → 匿名可读网关自身组件明细。

### 变更后

gateway `show-details: never`（六服务对齐）；`/actuator/health` 端点本身仍 200（status 聚合可见），仅组件明细不再对匿名暴露。

## ADDED 需求：README 上线前加固清单

鉴权小节新增「上线前加固清单」：开启鉴权（APP_AUTH_ENABLED=true 或 yml 覆盖）、JWT_SECRET 强随机注入（≥32 字节）、GOVERNANCE_TOKEN 注入、APP_SECURITY_STRICT=true（密钥缺失或鉴权关闭即拒启）、actuator 收敛（引用既有生产 profile 说明）。

## 验收断言

- 单测（纯 JVM 确定性，gateway-service +3 例 41→44）：strict=true + authEnabled=false → init() 抛 IllegalStateException（消息含两开关名）；strict=true + authEnabled=true + 凭证有效 → 不抛；strict=false + authEnabled=false → 不抛（lax 零扰动守护）。
- offline 全量 558 只增不减（预计 561=36/44/119/137/149/64/12）；`--static=record-service` 811 不增。
- 只改清单：C-01 恰 4 文件（AuthGlobalFilter.java / AuthGlobalFilterTest.java / gateway application.yml / README.md），main Java 仅 AuthGlobalFilter.java；C-02 恰 5 文件（tasks.json / 任务书 spec.md §7 / handoff.md / PLAN.md / findings-summary.md）。
- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql/ / 父 pom / record/user/verify/leaderboard/mapmatch 五服务 / 仓库根 .env / gateway 内其它类与既有测试类。
- findings-summary.md：F01 追加核实段（①已收口行号订正 + ②③本课题收口，F01 关闭）+ F08 网关残留收口标注，沿 F03/F05/F06 核实段格式。
- 台账与 handoff 不含安全收益数字、不枚举受保护 token 字面量。
