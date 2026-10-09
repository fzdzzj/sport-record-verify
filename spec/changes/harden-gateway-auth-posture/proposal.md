# TASK-191 harden-gateway-auth-posture 提案：strict 联动强制鉴权与网关 health 明细收口

## Why（现状与痛点）

findings F01（P0 安全，最后剩余 P0）+ F08 网关残留（P2，findings 原文建议「随下一轮治理面变更收尾」）。

现状实证（2026-10-09，指导侧亲核，行号按当前 HEAD）：

- **F01 改法①已在既有变更落地**（findings 2026-09-23 档案行号漂移未复核实致误判）：`AuthGlobalFilter` 降级/白名单分支（:143-145）已调 `stripIdentityHeaders()`（:195-204）剥离外部 X-User-Id/X-Role；归档变更 `add-auth-degrade-header-strip` 产物；已有 3 个单测覆盖（degradeDisabledStripsForeignIdentityHeaders / whitelistedPathStripsForeignIdentityHeaders / forgedIdentityOnAuthedPathIsOverridden）——F01 的伪造路径已被阻断
- **F01 改法②未落地**：`app.auth.enabled` 默认 false（gateway application.yml :114），全仓无 profile 概念、无生产姿态硬约束——部署者忘记开启鉴权时无任何启动期拦截（运行期仅治理路径失败关闭，业务面裸透传）
- **F01 改法③未落地**：README :190 仅描述降级开关语义（「默认 false 兼容压测」），无「上线前必须开启」检查清单
- **F08 网关残留**：gateway application.yml :164 `show-details: always`——其余 5 个后端服务均已 never（user:109 / leaderboard:133 / mapmatch:78 / verify:199 / record properties），网关 health 在白名单内免 token（:126 `/actuator/health` 精确匹配放行）→ 匿名可读网关自身组件明细（与 TASK-125 收口目标不一致的最后一处）
- **既有锚点**：`app.security.strict` 开关（TASK-126 / add-strict-secret-fail-fast）已实现「strict=true 时治理凭证缺失即启动失败」（AuthGlobalFilter.init :102-105）——生产姿态硬约束的机制先例已在位，本课题将鉴权开关纳入同一联动

## What（方案）

**strict 联动硬校验（生产姿态禁止关鉴权）+ README 上线检查清单 + F08 网关 health 明细一行收口 + findings 档案核实标注**，改动面 4+5 文件。

| 决策点 | 裁决 | 理由 |
| --- | --- | --- |
| prod 强制形态 | **strict 联动硬校验**：`app.security.strict=true` 且 `app.auth.enabled=false` → init() 抛 IllegalStateException 拒绝启动 | 复用 TASK-126 既有 strict 语义（生产姿态 = 密钥齐 + 鉴权开，身份边界与密钥同列硬约束）；语义真「强制」（显式覆盖也拦不住），零新增配置键；用户已裁决 |
| 检查顺序 | auth.enabled 联动检查置于 governanceToken 检查**之前** | 身份边界优先于密钥完整性的报错排序；两者同为启动期失败，顺序不影响结果只影响首个报错信息 |
| 本地演示零扰动 | `strict` 默认 false（既有值不动），false 时零校验零扰动 | 既有本地演示/压测口径（auth.enabled=false 兼容压测脚本）完全不变；只有显式声明生产姿态（strict=true）才触发硬约束 |
| F08 收口形态 | `show-details: always` → `never`（一行 + 注释） | 与其余 5 服务对齐（TASK-125 目标「六服务 always→never」补齐最后一处）；health 端点本身仍可用（status 聚合可见，组件明细不泄漏） |
| findings 档案更新 | F01 追加核实段（①已收口 + ②③本课题收口 + 行号订正）+ F08 追加网关残留收口标注 | 沿 F03/F05/F06 核实标注先例（TASK-128/130）；档案与现实对齐防后续误判 |
| profile 方案 | 不做 application-prod.yml | 用户裁决单选 strict 联动；双保险（profile+strict）会造成两套生产姿态声明口径，维护面翻倍无增益 |

**改动面（C-01 4 文件 + C-02 5 文件，main Java 仅 1 文件）**：

| 层 | 改动 |
| --- | --- |
| 网关过滤器 | AuthGlobalFilter.java：init() 头部加 strictMode && !authEnabled → IllegalStateException；Javadoc 补联动说明 |
| 网关测试 | AuthGlobalFilterTest.java：+3 例（strict+disabled 拒启 / strict+enabled 干净启动 / lax 默认零扰动） |
| 网关配置 | application.yml：show-details always→never + 注释；strict 注释段补联动说明（auth.enabled 纳入硬约束清单） |
| 文档 | README：鉴权小节加「上线前加固清单」（开鉴权 / 注密钥 / strict=true / actuator 收敛引用） |
| 台账（C-02） | tasks.json 全勾 + spec §7 回填 + handoff.md 新建 + PLAN.md 纯追加 + findings-summary.md F01/F08 核实标注 |

**单测（+3，纯 JVM 确定性，落 gateway-service）**：

| 用例 | 断言 |
| --- | --- |
| strictModeWithAuthDisabledFailsStartup | strict=true + authEnabled=false → init() 抛 IllegalStateException（消息含两开关名） |
| strictModeWithAuthEnabledInitializesCleanly | strict=true + authEnabled=true + governanceToken 有效 → init() 不抛（联动不误伤合法生产姿态） |
| laxModeKeepsLocalDemoUnaffected | strict=false + authEnabled=false → init() 不抛（本地演示/压测零扰动守护，防联动误扩到 lax 姿态） |

## 边界（明确不做）

- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / record-service / user-service / verify-service / leaderboard-service / mapmatch-service / 仓库根 .env / gateway 内其它类（JwtTokenParser / SentinelGatewayRuleConfig / RequestIdGlobalFilter / GatewayCorsConfig / GatewayApplication）与既有测试类（ActuatorWhitelistNarrowTest 等不动）
- gateway application.yml 仅动两处：show-details 一行 + 注释段；`app.auth.enabled: false` 默认值**不动**（本地演示口径）、whitelist / admin.paths / sentinel / 路由 / CORS 段零改动
- 不引入 Spring profile / application-prod.yml / 新配置键 / 新依赖
- 不 claim 任何安全收益数字（无量化评估依据，只登记机制性事实）
- F10（JWT 硬编码兜底）/ F23（服务侧角色校验）等其它 findings 项不并入（独立课题）

## 风险

| 风险 | 缓解 |
| --- | --- |
| strict 联动误伤既有合法启动路径 | lax（strict=false）默认路径零校验（用例 3 守护）；strict=true 场景本就要求密钥齐（TASK-126），开鉴权是同姿态的自然前提；全仓 grep 确认 strict=true 无本地使用点 |
| 检查顺序调整影响既有 strict 报错语义 | governanceToken 检查原样保留（仅后移一位）；既有 strict 场景若同时缺密钥且关鉴权，报错从密钥缺失变为鉴权关闭——语义更优先，登记偏差说明即可 |
| show-details never 后网关 health 探针语义变化 | /actuator/health 本身仍 200（status 聚合）；仅组件明细（DB/Redis 连通细节）不再对匿名暴露——这正是 F08 目标；K8s/监控探针用 status 不受影响 |
| findings 更新行号再次漂移 | 标注带「行号按 TASK-191 时点」时点说明（沿既有核实段惯例） |

## 验收（摘要）

gateway 3 例新单测全绿（41→44）；Java offline 全量 558 只增不减（预计 561，36/44/119/137/149/64/12）；`--static=record-service` 811 不增（gateway 非 static 目标模块，checkstyle 不扫其 main 之外的增量——AuthGlobalFilter 改动属 gateway 模块，需跑 `--static` 确认 811 基线无涉）；yml 双处与 README 改动经 diff 核验零越界；词面门 ZERO_HIT；token 29 项只增不减；契约门 rc=0；findings F01/F08 核实标注与代码实态逐字一致；CI 第 33 次外部门槛绿。
