# TASK-251 任务书（spec.md）

> **课题**：E 系批1——认证/鉴权面三处破口治理（E1 内部接口凭证经网关可达自助提权 + E2 改密经网关恒 401 主链路不可用 + E3 通知清理端点零鉴权跨用户删除）  
> **级别**：FULL 档（网关鉴权语义 + auth 凭证主链路 + 跨用户数据删除——三处均安全语义改动，禁止降级；多模块 gateway/user + compose/CI 占位基建 → 独立复核 + 全量逐位离线门禁）  
> **责任服务**：`gateway-service`、`user-service`、`compose`、`CI 占位 env`、文档  
> **开工基线**：开工时 `main` HEAD = TASK-252 立项笔（实际哈希以实施派发包标注为准，C-02 回填；工作树干净）  
> **状态**：待实施  

---

## 1. 背景与目标

E 系只读立项侦察（2026-10-16 归档，BACKLOG.md E 系节）产出三项高危破口，指导侧亲验坐实：

- **E1**：网关全分支只剥离治理头与 X-User-Id/X-Role、从不清 `X-Internal-Token`；route-user `StripPrefix=1` 使外部 `/user/internal/**` 可达上游 internal 面（六处「网关无 /internal 路由」书面前提被证伪）；密钥默认字面量 + strict=false 不拒启 → 外部持公开默认串可自授 ADMIN；
- **E2**：`/api/auth/**` 整段白名单分支 stripIdentityHeaders 删 X-User-Id，而改密端点强制取该头缺失即 401 → authEnabled 真假两条路径均失败，改密主链路 100% 不可用（测试面自行 addHeader 从不过网关而掩盖）；
- **E3**：`DELETE /api/notifications/expired` 全控制器唯一不 resolveUserId 不判角色的端点，mapper 全表 DELETE 无 user_id，治理面清单不含该路径 → 任意 token 可分批清空全平台已读通知。

**目标**：关闭经网关的自助提权路径；恢复改密主链路（由网关鉴权分支注入身份认定）；治理清理端点收敛 ADMIN 双层防线。

## 2. 严格改动文件白名单（恰 14 项，禁超出）

1. `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`（R3 头剥离+路径拒绝；R2 白名单分支行为不变）
2. `gateway-service/src/main/resources/application.yml`（R1 admin.paths 追加；R2 whitelist 收窄）
3. `gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`（R2/R3 用例；若实际路径/类名不同按实际，缺则新建）
4. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`（R1 服务层角色门）
5. `user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java`（R1 用例；条件触碰：存在则扩展，不存在则新建）
6. `user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java`（条件触碰：仅当白名单收窄需适配既有用例语义，断言禁删弱）
7. `web/src/api/client.ts`（条件触碰：预期零改动——旧路径收窄后走鉴权分支由网关注入头即通；仅当核实调用形态确需变更才触碰，handoff 注明实际触碰与否）
8. `docker-compose.services.yml`（R3 INTERNAL_API_TOKEN 必填插值）
9. `.github/workflows/ci.yml`（条件触碰：仅「Provision placeholder env」步骤补 INTERNAL_API_TOKEN 占位一行）
10. `.env.example`（条件触碰：仅当仓内存在该文件）
11. `README.md`（R3 curl 示例与「网关无 /internal 路由」表述订正）
12. `docs/adr/0007-鉴权设计.md`（条件触碰：补记式订正一行，不改原结论结构）
13. `work/mailbox/tasks/TASK-251/spec.md`（C-02 回填占位符）
14. `work/mailbox/tasks/TASK-251/handoff.md`（C-02 回填五节）

## 3. 判别式

### R1（E3）通知清理端点收敛 ADMIN 双层防线
- `gateway/application.yml` admin.paths 追加 `/user/api/notifications/expired`（精确串，沿 `/leaderboard/api/leaderboard/daily` 先例无 `**` 后缀语义）；
- `NotificationController.cleanExpiredNotifications` 加服务层角色门：方法签名加 `HttpServletRequest`，读 `X-Role` 头判定 `ADMIN`——非 ADMIN 或头缺失一律 `BizException(ResultCode.FORBIDDEN)` 失败关闭（与 resolveUserId 同款头认定模式）；Javadoc 注明「网关 admin.paths 前置拦截 + 服务层角色门兜底」双层语义；
- 测试三用例：ADMIN 头放行（mock service 断言调用透传）、USER 头 403、无头 403；网关侧 adminPaths 相关既有测试如有清单断言同步。

### R2（E2）改密端点迁出白名单走鉴权分支
- **开工先枚举**：`AuthController` 全部端点逐一核全，二分为「公开集」（无需 Bearer 身份：register/login/refresh 等发 token 入口）与「鉴权集」（change-password/logout 等带 Bearer 语义端点）；枚举不清晰、存在无法二分的特殊语义端点 → **停止修改并汇报**；
- `gateway/application.yml` whitelist 由 `/api/auth/**` 收窄为公开集逐端点精确清单；收窄后 change-password 走鉴权分支：网关剥离伪造头、校验 JWT、注入 X-User-Id → `resolveUserId` 语义零改动即通；logout 若保留白名单须在 handoff 注明「token 吊销语义不依赖身份校验」理由；
- 前端预期零改动（client.ts 统一 Bearer 拦截器 + 旧路径收窄后走鉴权即恢复）；实施侧核实并在 handoff 注明触碰与否；
- 测试：AuthGlobalFilterTest 补「change-password 不再白名单放行 → 鉴权分支注入 X-User-Id」「login/refresh 等仍白名单放行且剥离身份头」用例；AuthControllerTest 既有用例断言零删弱。

### R3（E1）internal 凭证剥离 + 路径拒绝 + 密钥必填
- `AuthGlobalFilter` 入口清理段（现 L129-132 剥离治理头同一处）追加 remove `X-Internal-Token`（常量引用 common 既有 `InternalApiHeaders.TOKEN`，common 零改动），覆盖全分支；
- 路径拒绝：白名单判断之前解析 path 段，任一段等于 `internal` → `reject(exchange, HttpStatus.NOT_FOUND, ...)` + warn 日志（外部不可达语义，不泄露存在性；沿 GatewayExceptionHandler 404 映射口径）；该拒绝先于白名单/鉴权分支，覆盖全部七条路由的未来新增 internal 面；
- `docker-compose.services.yml`：使用 INTERNAL_API_TOKEN 的服务环境变量改必填插值（对齐仓内 GOVERNANCE_TOKEN 现有插值形态口径，实施侧开工核对齐）；
- `.github/workflows/ci.yml` 「Provision placeholder env」步骤补 INTERNAL_API_TOKEN 占位行（compose config 门禁 CI 步骤不因缺变量红）；
- README.md 订正：README curl 示例改「直连 user-service 端口」口径或删除；「网关无 /internal 路由」类表述订正为「网关层已拒绝外部 internal 路径」新防线描述；docs/adr/0007 L67 补记式订正一行；
- 测试：AuthGlobalFilterTest 补「入站 X-Internal-Token 全分支剥离」「/user/internal/** 模拟路径 → 404 拒绝」用例。

## 4. 门禁（FULL 档全量逐位，全部 rc=0 并留证）

1. **后端全模块**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test`——全模块逐位，测试数只增不减（基线：common 52 / gateway 62 / user 202 / record 300 / verify 182 / leaderboard 104 / mapmatch 29；本课题预期 gateway/user 有新增，其余模块凭白名单外零 diff 论证不变）；
2. **compose 校验**：`docker compose -f docker-compose.yml -f docker-compose.services.yml config` rc=0——注意 R3 必填插值后本地仓根 `.env`（未跟踪，不入库）须先补 INTERNAL_API_TOKEN 行；
3. **前端回归**：`cd web; pnpm run type-check; pnpm test` 全绿（基线 4 文件 61 测；client.ts 若触碰须含 type-check 证据）；
4. **契约门**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-251 --baseline=<开工 HEAD 哈希，见派发包>` rc=0；
5. **token 门**：29 项受保护字面量 `git grep -cF` tracked 全量口径，相对开工基线只增不减（清单勿枚举字面量原文）；
6. **词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机显式 `-E` + `.trae/tmp/` 合成探针阳性对照）；
7. **`git diff --check`**：提交前净。

## 5. 双提交规范

- **C-01 实施笔**：`fix(gateway,user): 网关剥离internal凭证与拒绝internal路径及改密迁出白名单与通知清理收敛ADMIN（TASK-251）`——仅白名单 1-12 中实际触碰者；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-251实施完成与门禁回填（TASK-251）`——仅文件 13、14；回填 handoff 五节并把开工基线占位符替换为实际哈希；
- 已知受限（handoff §4 须登记）：① 真实网关→上游头注入/剥离端到端联调未做（纯 JVM 网关单测+控制器单测双层拼接论证，沿 TASK-249/250 口径）；② strict 模式默认 false 保持（compose 必填插值保障 compose 部署路径真值注入；裸机本地 demo 仍用默认串，属本地演示姿态）；③ 内网直连服务端口层面的 internal 防护仍依赖网络边界（端口绑 127.0.0.1），本课题仅消解「经网关绕入」路径；④ README/ADR 订正为补记式，历史叙述结构保留。
