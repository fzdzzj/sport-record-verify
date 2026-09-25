# TASK-136 第二阶段：服务直连治理凭证扩围

## 目标

在第一阶段网关别名准入（已提交 `1f95654`）之上，为治理面增加**独立**的网关专用凭证，阻止绕过网关直连服务端口：

- 凭证头：`X-Gateway-Governance-Token`（**不复用** Feign 的 `X-Internal-Token`）。
- 服务侧**不以** `X-Role` 作授权依据。
- 网关：所有入站分支先清除客户端自带的治理凭证头；仅当 `auth.enabled`、JWT 有效、`role=ADMIN`、`admin.enabled`、专用密钥均有效时，向治理目标注入网关配置的令牌。任一前提不成立 → 治理目标失败关闭；普通用户路径不注入。
- verify-service 保护本地 `/api/appeals/**`、`/rules/**`。
- leaderboard-service **只精确保护** `/api/leaderboard/daily`（不得纳入普通 `/api/leaderboard` 总榜/好友榜）。
- 保留现有 `/internal/**` Feign 用法，不全局剥离其旧 token。
- 新密钥不得使用仓库演示默认值；`app.security.strict=true` 时，网关及配置了受保护路径的服务缺密钥须启动失败；非严格模式缺密钥也必须拒绝治理请求，不能静默放行。

## 路径对照（网关 → 本地）

| 治理目标 | 网关路径 | 服务本地路径 | 方法 | 当前校验 | 合法调用方 |
| --- | --- | --- | --- | --- | --- |
| 申诉终判 | `/admin/api/appeals/**` 与别名 `/verify/api/appeals/**` | verify `/api/appeals/**` | POST（review） | 网关：JWT+ADMIN+治理令牌注入；服务：治理令牌 | 经网关的 ADMIN |
| 规则版本 | `/verify/rules/**` | verify `/rules/**` | POST/PATCH | 同上 | 经网关的 ADMIN |
| 榜单日报 | `/leaderboard/api/leaderboard/daily` | leaderboard `/api/leaderboard/daily` | GET | 同上；服务侧精确匹配 daily | 经网关的 ADMIN |

**不得误保护**：`GET /api/leaderboard`（`type=overall|friend`）仍为用户面；现有 `/internal/**` 仍只校验 `X-Internal-Token`。

## 方案选择（已采纳②）

| | ①复用 `X-Internal-Token` | ②独立 `X-Gateway-Governance-Token`（推荐/已实现） |
| --- | --- | --- |
| 能否证明来自网关 | 否：持有内部 token 的任意 Feign/运维调用方可伪称 | 仅证明持有网关配置的专用密钥；**不是签名** |
| 阻止外部伪造头 | 网关若不清空，客户端可伪造；且与 Feign 密钥耦合 | 网关入口统一剥离后仅 ADMIN 治理路径再注入 |
| `auth.enabled=false` | 若仍透传，直连与降级面更难分 | 治理路径失败关闭（本阶段明确要求） |
| 演示默认密钥 | 内部 token 仍有 `local-demo-internal-token` | 治理令牌无演示默认；未配置用哨兵/空值并拒绝 |
| 分步部署 | 扩围 `/internal` 语义易误伤 Feign | **网关先、服务后**：先注入再校验，可避免合法网关请求被新过滤器拒 |

剩余风险（须记账）：专用令牌**非签名**，持有者可复用/重放；不绑定 caller、无时效、无路径绑定。

## 本阶段变更

- `common/.../governance/GovernanceApiHeaders.java`、`GovernanceApiAuthFilter.java` + 单测。
- `gateway-service/.../AuthGlobalFilter.java`：剥离伪造治理头、治理失败关闭、ADMIN 注入；`application.yml` 增加 `app.governance.token`。
- `verify-service` / `leaderboard-service` `application.yml`：`protected-paths` 与无演示默认的 token 占位。
- 网关相关既有配置一致性测试补齐 `governanceToken`，避免 ADMIN 误红。
- 装配与解析补强：verify/leaderboard 新增真实 yml 装配测试（`GovernanceWiringTest`）；`@Value` 对 `List<String>` 不做逗号切分（生产上下文无 beanFactory ConversionService），两过滤器改为收 String 原文、`@PostConstruct` 自行切分，否则 `protected-paths` 与网关 `whitelist`/`admin.paths` 在生产永不命中。

## 受控红绿口径

先红后绿判别式（本地 Mock / 过滤器单测）：

1. 三治理目标直连无令牌 → 403
2. 仅伪造 `X-Role=ADMIN` → 403
3. 错误/空令牌 → 403
4. 网关剥离客户端伪造治理凭证后注入配置令牌
5. USER JWT 治理路径 403；ADMIN + 有效令牌配置可达
6. `auth.enabled=false` / `admin.enabled=false` → 治理失败关闭
7. 普通 `/api/leaderboard` 与 `/internal/**` 不被治理过滤器误伤

整体 offline：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl common,gateway-service,verify-service,leaderboard-service test
```

## 部署顺序

1. 先部署网关（配置同一 `GOVERNANCE_TOKEN`，开始注入）。
2. 再部署 verify / leaderboard（开始校验同一令牌）。
3. 反向顺序会在短暂窗口内阻断合法网关治理请求。

## 停止边界

- 不提交、不 push、不建 PR；不记录真实密钥；不引新第三方依赖。
- 不把读取 `X-Role` 当作服务侧验权；不把共享/专用 token 称为「网关签名」。
- 未发现服务间 Feign 直调三治理端点的合法调用方（Feign 均走 `/internal` 或 mapmatch `/match`）；若后续发现无法安全分步部署的证据须停并回报。
