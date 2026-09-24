# TASK-136 Handoff

## 结论

TASK-136 第一阶段已完成网关治理面别名准入的最小修正：`/verify/api/appeals/**` 已纳入 `AuthGlobalFilter` 的管理员路径判别式，并同步写入实际 `application.yml` 配置。有效 USER JWT 在鉴权开启且管理员角色校验开启时被网关拒绝（403）；ADMIN JWT 对该别名通过过滤器并保留既有身份头注入行为。

原 `/admin/**`、`/verify/rules/**` 行为保持；现有 `/verify/**` 和 `/admin/**` 路由未改。

## 编号、基线与外部状态

- 任务编号：`TASK-136`。
- 开工基线：`c57e69dab39875f79b81312b6453a596fa86272f`（`docs(mailbox): 订正 TASK-135 提交绑定`）。
- 当前业务/测试/台账修改仍在工作树，未形成新的 commit；未 push、未建 PR。
- `.trae/` 为既有未跟踪目录，本任务未触碰。

## 根因与最小修正

- `route-verify-service` 原已匹配 `/verify/**` 并 `StripPrefix=1`；本阶段没有新增路由。
- 原 `app.auth.admin.paths` 与 `AuthGlobalFilter` 默认值均遗漏 `/verify/api/appeals/**`。
- 原过滤器对该别名仅做 Bearer/JWT 校验，因未命中 `isAdminPath` 而继续透传；基线判别式实测响应状态为 `null`。
- 修正只增加一个治理路径模式到代码默认值和实际 YAML，并补充对应红绿测试。

## 红绿证据（本轮不重复跑测试）

### 原实现红测（辅助证据：裸 Maven）

命令：

```text
mvn -pl gateway-service -Dtest=AuthGlobalFilterTest test
```

结果：退出码 `1`。`userRoleVerifyAppealAliasReturns403` 失败：期望 `403 FORBIDDEN`，实际 `null`。该结果证明原过滤器继续调用链，旧实现放行了 USER JWT 的别名请求。

### 修正后已有绿测（指导侧仓库脚本 offline）

命令：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test
```

结果：退出码 `0`；目标模块 `35/0/0/0`（Failures/Errors/Skipped 均为 0）；`BUILD SUCCESS`。

指导侧已亲自执行该仓库脚本入口；前序 agent 执行的 `mvn -pl gateway-service test` 同样为 `35/0/0/0`，仅作为辅助裸 Maven 证据。本轮台账订正不重复执行 Maven。

### 脚本入口与外部覆盖边界

- 修正后主证据是 `bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test`。
- 脚本 `--mode=online`、CI、真实服务直连均未覆盖；不把 offline 结果升级为 online/CI 结论。

## 配置与契约核对内容

新增/更新测试约束了：

- `AuthGlobalFilter` 的 `@Value` 默认路径包含 `/admin/**`、`/verify/rules/**`、`/verify/api/appeals/**`；
- classpath 实际 `application.yml` 的 `app.auth.admin.paths` 同时包含上述路径；
- `/verify/**`、`/admin/**` 原路由仍存在；
- 使用实际 YAML 路径灌入过滤器后，别名 USER=403、ADMIN=放行；原 `/admin/**`、`/verify/rules/**` USER=403、ADMIN=放行。

## 实际改动清单

- `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`
- `gateway-service/src/main/resources/application.yml`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/VerifyAppealReviewAdminOnlyTest.java`
- `work/mailbox/tasks/TASK-136/spec.md`
- `work/mailbox/tasks/TASK-136/handoff.md`
- `work/mailbox/PLAN.md`

另有开工前已存在、未触碰的未跟踪目录：`.trae/`。

## 未完成与未覆盖

- **服务直连凭证扩围仍未完成**：没有修改服务侧 token/凭证校验，也没有覆盖服务端口绕过网关的风险；不能声称该风险已解决。
- 未执行真实 Nacos 服务发现、网关到 verify-service 的端到端联调或服务端口直连验证。
- `app.auth.enabled` 仓库默认值仍为 `false`；本阶段测试场景显式设置为 `true`，`app.auth.admin.enabled` 场景显式开启。没有改变默认降级开关。
- 脚本 online/CI 外部门槛未覆盖；未 push、未建 PR。

## 契约状态

本轮仅在台账写入完成后执行 mailbox 契约核对，不重复执行 Maven 测试。使用 Git Bash 执行：`bash scripts/verify/mailbox-contract.sh --baseline=HEAD`，退出码 `1`。`TASK-136` 判据 A 两件套齐全、判据 B 通过（只改清单与实际改动集一致）；总体失败来自既有在途任务 `TASK-018`、`TASK-106`、`TASK-109`、`TASK-132`、`TASK-133`、`TASK-134`、`TASK-135` 与共享工作树/历史清单交叠，输出同时显示本任务实际 7 项改动均已被 TASK-136 清单声明。首次从 PowerShell 直接调用 `bash` 因 WSL2 镜像缺失退出码 `1`，不作为契约判定；随后使用 `D:\git\Git\bin\bash.exe` 完成实际契约核对。
