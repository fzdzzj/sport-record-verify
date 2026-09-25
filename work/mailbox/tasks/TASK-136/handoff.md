# TASK-136 Handoff

## 结论

TASK-136 **第二阶段**（服务直连治理凭证扩围）已完成最小实现、offline 定向验证与真实 yml 装配验证，业务修订、测试与本台账合并为**单一本地提交**（提交主题 `fix(governance): gate direct service governance with dedicated gateway token`），**未 push、未建 PR**。第一阶段网关别名准入已绑定提交 `1f95654fb094a7457b7a4df11bf58301be385e00`（`fix(gateway): gate verify appeal alias by admin role`）；台账中旧句「当前业务/测试/台账修改仍在工作树，未形成新的 commit」仅适用于第一阶段收口前状态，现已订正。

本阶段采用独立头 `X-Gateway-Governance-Token`，**不复用** `X-Internal-Token`，服务侧不以 `X-Role` 授权。专用令牌**不是签名**：持有者可复用是剩余风险。

## 编号、基线与外部状态

- 任务编号：`TASK-136`。
- 第二阶段开工/对照基线：`1f95654fb094a7457b7a4df11bf58301be385e00`。
- 第二阶段业务修订、测试与本台账已合并为**单一本地提交**；**未 push、未建 PR**；提交哈希由任务回传（可 `git log --grep="gate direct service governance"` 查得）。
- `.trae/` 为既有未跟踪目录，本任务未触碰。
- 临时日志 `.tmp-task136-verify.log` 仅供本轮取证，勿入库。

## 路径与误保护边界（已核对）

| 治理目标 | 网关路径 | 服务本地 | 方法 | 校验 | 合法调用方 |
| --- | --- | --- | --- | --- | --- |
| 申诉终判 | `/admin/api/appeals/**`、`/verify/api/appeals/**` | `/api/appeals/**` | POST | 网关 JWT+ADMIN+注入治理令牌；服务校验治理令牌 | 经网关 ADMIN |
| 规则版本 | `/verify/rules/**` | `/rules/**` | POST/PATCH | 同上 | 经网关 ADMIN |
| 榜单日报 | `/leaderboard/api/leaderboard/daily` | `/api/leaderboard/daily`（精确） | GET | 同上 | 经网关 ADMIN |

不得误保护：`GET /api/leaderboard`（overall/friend）；现有 `/internal/**` + `X-Internal-Token` Feign 行为保持。盘点：api 模块 Feign 契约无直调上述三治理端点。

## 实现要点

- **网关**：入口统一 `remove(X-Gateway-Governance-Token)`；治理路径在 `auth.enabled=false` 或 `admin.enabled=false` 或令牌未配置时失败关闭；仅 ADMIN 治理路径注入配置令牌。
- **服务**：`GovernanceApiAuthFilter`（common，`scanBasePackages=com.sportverify`）；`protected-paths` 空则对无治理面服务近似 no-op；缺/空/哨兵/错令牌 → 403；strict 且配置了保护路径时缺令牌启动失败。
- **列表绑定运行时修复（装配测试暴露，本轮修正）**：`@Value` 注入 `List<String>` 在生产上下文不按逗号切分（Spring Boot 3.2.4 未注册 beanFactory ConversionService，`CustomCollectionEditor` 把整串当单元素），`protected-paths`/网关 `whitelist`/`admin.paths` 的 yml 配置将永不命中。两个过滤器均改为 `@Value` 收 String 原文、`@PostConstruct` 自行切分（去空白、丢空段），网关白名单/治理路径与治理保护在生产才真实生效；此为网关既有白名单/治理面路径的潜伏缺陷修正，属第二阶段端到端成立的前提。
- **配置**：`GOVERNANCE_TOKEN`；服务 YAML 回落 `__GOVERNANCE_TOKEN_UNSET__`；网关回落空串；均无演示默认明文。
- **部署顺序**：网关先（注入）→ verify/leaderboard 后（校验）。反向会阻断合法治理请求。

## 红绿证据（本轮，行为红口径）

先红（行为红：装配测试对基线 yml 实跑失败；「删类导致编译失败」不记为行为红。证据后随即逐字节恢复工作树）：

- 把 verify / leaderboard 两份 `application.yml` 临时还原为基线 `1f95654` 版本（无 `app.governance` 配置块，即旧行为：服务不校验治理凭证），用仓库脚本分别跑 `--pl verify-service test` 与 `--pl leaderboard-service test` → 退出码均 `1`，新增装配测试按预期红：治理路径无令牌 `expected: <403> but was: <200>`；`GET /api/leaderboard` 放行断言在红绿两侧均通过（不受保护）。
- 网关旧行为不再单独制造行为红：`adminCheckDisabledUserPasses`→`adminCheckDisabledGovernanceFailsClosed` 等判别式翻转已在测试中固定。

后绿：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl common,gateway-service,verify-service,leaderboard-service test
```

结果：退出码 `0`、`BUILD SUCCESS`；模块汇总 common `36/0/0/0`、gateway `41/0/0/0`、verify `89/0/0/0`、leaderboard `59/0/0/0`（api 无单测）。日志：`.tmp-task136-verify.log`。

定向覆盖（过滤器/网关单测，非真实跨进程）：

- 无令牌 / 伪造 `X-Role` / 错令牌 / 空令牌 → 403
- 有效令牌放行三治理路径
- 总榜路径与 `/internal/**` 不被治理过滤器保护
- 网关剥离客户端伪造治理头并注入配置令牌；USER 路径不注入；USER 路径自带伪造治理头也被剥离（复核会话补测 `userPathForgedGovernanceTokenStripped`）
- `auth.enabled=false` / `admin.enabled=false` / 缺治理令牌 → 治理失败关闭
- strict + 有 protected-paths 缺令牌启动失败；无 protected-paths 的服务不因缺治理令牌启动失败
- **真实 yml 装配**（verify/leaderboard `GovernanceWiringTest`：加载各服务 classpath 真实 application.yml 绑定 `protected-paths` 后驱动过滤器）：无令牌访问申诉/规则/日报 → 403；`GET /api/leaderboard` 不误伤
- **逗号切分解析**（`initSplitsCommaSeparated*` 两测）：`@Value` String 原文 → 模式列表（去空白、丢空段）

顺带修正：`LeaderboardDailyAdminOnlyTest` 在注入治理令牌前会因缺凭证对 ADMIN 误报 403；已补 `governanceToken` 测试夹具。复核会话另订正 `RuleVersionController` javadoc（删除已过时的「本服务不自行校验 token / 角色由 X-Role 授权」，服务侧现校验治理令牌）并清理本阶段触碰文件的末尾空行。

## 实际改动清单

- `common/src/main/java/com/sportverify/common/governance/GovernanceApiHeaders.java`（新）
- `common/src/main/java/com/sportverify/common/governance/GovernanceApiAuthFilter.java`（新）
- `common/src/test/java/com/sportverify/common/governance/GovernanceApiAuthFilterTest.java`（新）
- `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`
- `gateway-service/src/main/resources/application.yml`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/LeaderboardDailyAdminOnlyTest.java`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/VerifyAppealReviewAdminOnlyTest.java`
- `verify-service/src/main/resources/application.yml`
- `verify-service/src/main/java/com/sportverify/verify/controller/RuleVersionController.java`
- `verify-service/src/test/java/com/sportverify/verify/config/GovernanceWiringTest.java`（新）
- `leaderboard-service/src/main/resources/application.yml`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/GovernanceWiringTest.java`（新）
- `work/mailbox/tasks/TASK-136/spec.md`
- `work/mailbox/tasks/TASK-136/handoff.md`
- `work/mailbox/PLAN.md`

未触碰：`.trae/`。另对本阶段触碰的过滤器主类与三份 yml 做了末尾空行清理。

## 未覆盖与已知影响（不得写成通过）

- **真实跨服务 / 网关→下游联调 / 服务端口直连冒烟**：未跑。
- **`--mode=online`、CI、push/PR**：未覆盖；**未达外部门槛**。
- **仓库默认 `app.auth.enabled=false`**：治理路径现失败关闭；既有经网关、不带 JWT 的规则灰度冒烟（`scripts/smoke/smoke-a.sh` 等）在默认降级配置下会 403——属本阶段显式安全语义，需在开启鉴权并注入同一 `GOVERNANCE_TOKEN` 后重跑；不在本轮 offline 单测范围内。
- **`docker-compose.services.yml` 头注释仍写「服务侧按约定不校验 token」**：已过时，本轮未改该文件（避免扩 scope）；后续文档微清理。
- compose/`.env` 未预置 `GOVERNANCE_TOKEN`：上线/本地联调前必须显式注入，且不得把真实密钥写入台账。

## 契约状态

提交后实跑（无参数，基线默认 HEAD）：工作树相对基线的改动集与回传清单**无交叠 → 视为已收口**（判据 B 口径），TASK-136 两件套齐全（判据 A）。提交前最后一轮在途核对（`--baseline=HEAD`）为：总体 `rc=1`，TASK-136 判据 B 通过，总体失败来自 TASK-135 在途清单过期（其改动早已提交 `2df9131`，属共享工作树/历史清单交叠，与本任务无关，未代为订正）。提交哈希、文件范围与契约退出码由任务回传。