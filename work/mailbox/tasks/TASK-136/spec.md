# TASK-136 第一阶段：治理面网关别名准入

## 目标

仅在 `gateway-service` 的治理面网关准入层，补齐申诉复核别名 `/verify/api/appeals/**` 的管理员角色准入：

- `app.auth.enabled=true` 且 `app.auth.admin.enabled=true` 时，带有效 `USER` JWT 访问 `/verify/api/appeals/1/review` 必须返回 HTTP 403；
- 带有效 `ADMIN` JWT 访问该别名应通过 `AuthGlobalFilter`，并按既有鉴权语义注入 `X-User-Id`/`X-Role`；
- 原 `/admin/**`、`/verify/rules/**` 治理面行为保持不变；
- 现有 `/verify/**` 路由保持不变，别名仍由该路由转发。

## 已核对事实

- `application.yml` 已有 `route-verify-service`：`Path=/verify/**` + `StripPrefix=1`。
- `application.yml` 已有 `route-admin-service`：`Path=/admin/**` + `StripPrefix=1`。
- 修正前 `app.auth.admin.paths` 仅覆盖 `/admin/**`、`/verify/rules/**` 和榜单日报路径，未覆盖 `/verify/api/appeals/**`。
- 修正前 `AuthGlobalFilter` 只对 `adminPaths` 命中的路径执行 `role=ADMIN` 判定，因此别名会完成 JWT 校验后继续放行。

## 本阶段变更

- `AuthGlobalFilter` 的代码默认 `adminPaths` 加入 `/verify/api/appeals/**`。
- `application.yml` 的实际 `app.auth.admin.paths` 加入 `/verify/api/appeals/**`。
- 新增过滤器回归测试，并新增“代码默认路径 ↔ 实际 YAML 配置 ↔ 过滤器行为”一致性测试，防止默认值与部署配置漂移。
- 不改服务侧凭证机制、不改 JWT 语义、不改 `/verify/**` 或 `/admin/**` 路由、不声称已解决服务直连风险。

## 受控红绿口径

### 原实现红测（辅助证据：裸 Maven）

在生产代码修正前，先加入别名判别式并执行：

```text
mvn -pl gateway-service -Dtest=AuthGlobalFilterTest test
```

结果：退出码 `1`。关键失败：`userRoleVerifyAppealAliasReturns403` 期望 `403 FORBIDDEN`，实际响应状态为 `null`；`null` 表示过滤器未写拒绝响应而继续调用过滤链，确认原实现放行 USER 别名请求。

### 修正后已有绿证据（指导侧仓库脚本 offline）

前序实现完成后已执行：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test
```

结果：退出码 `0`，目标模块 `35/0/0/0`（Failures/Errors/Skipped 均为 0），`BUILD SUCCESS`。

前序 agent 执行的 `mvn -pl gateway-service test` 同样为 `35/0/0/0`，仅作为辅助裸 Maven 证据；本台账以指导侧仓库脚本 offline 结果为修正后主证据。

本轮台账补录不重复执行 Maven 测试。

## 契约与验证边界

- 修正后主证据绑定到指导侧已实跑的仓库入口：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test
```

- 该脚本入口结果为退出码 `0`、`35/0/0/0`、`BUILD SUCCESS`；前序 agent 的裸 Maven 结果仅作辅助证据。
- 脚本 `--mode=online`、CI、真实服务直连均未覆盖；不把 offline 结果升级为 online/CI 结论。
- 未执行真实 Nacos/服务联调、下游 verify-service 调用、服务端口直连验证。
- 服务直连凭证扩围仍未完成；本阶段只治理网关别名准入，不能推导出直连服务风险已解决。

## 停止边界

- 不修改 `verify-service`、`user-service` 或其他服务侧凭证机制。
- 不修改 JWT claim、签名、过期、类型或签发语义。
- 不将网关准入修正描述为服务直连风险修复。
- 不修改 `.trae/`，不 push，不建 PR。
