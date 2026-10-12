# TASK-205 回传交接（web 凭证面与 CORS 生产姿态——refresh cookie 化 + 前端适配 + CORS 环境变量化）

## 1. 编号、基线与外部状态

- 课题：harden-web-credential-posture（web 凭证面与 CORS 生产姿态）。
- 开工基线：`206ea6c`（派发笔，含 proposal/tasks.json 与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- C-01 实施笔：`6d0318d`（`fix(auth): refresh凭证cookie化与CORS生产姿态（TASK-205）`，恰 7 文件 = §4 白名单全量）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-205web凭证面加固验收与台账闭环（TASK-205）`，恰 4 文件）。
- 两笔均**未 push、未建 PR**；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 实施笔 7 文件 = §4 白名单全量；C-02 台账笔 4 文件）

- user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
- user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java（新建）
- gateway-service/src/main/resources/application.yml
- web/src/utils/token.ts
- web/src/api/client.ts
- web/src/pages/login.page.vue
- README.md
- spec/changes/harden-web-credential-posture/tasks.json
- work/mailbox/tasks/TASK-205/spec.md
- work/mailbox/tasks/TASK-205/handoff.md
- work/mailbox/PLAN.md

## 3. 红绿判别式（纯 JVM Mockito + MockHttpServletRequest/Response，不启 Spring 上下文）

AuthControllerTest（user-service，新建）：
- **R1a 红**：login 成功 → 响应 Set-Cookie 含 `refresh_token=<值>`、`HttpOnly`、`SameSite=Lax`、`Path=/api/auth`、`Max-Age=604800`（maxAge 对齐 refresh 存活 TTL 7 天）；且返回 body 的 `data.refreshToken == null`。
- **R1b 红**：refresh 请求 cookie 携旧值、body 携异值 → authService.refresh 收到的是 cookie 值（cookie 优先）；成功响应新 Set-Cookie + body 置 null。
- **R1c 红**：logout 请求 cookie 携值 → authService.logout 收到 cookie 值；响应 Set-Cookie 同名 `Max-Age=0`。
- **R1d 红**：refresh/logout 仅 body 携值（无 cookie）→ 收到 body 值（兜底通道）。
- 实测：AuthControllerTest 新增 5 例全绿。

前端/配置（vite build + 文件实证，见 §6）：
- **R2 红**：token.ts 无 refresh `setItem`（refresh 键不再持久化）；client.ts refresh 调用含 `withCredentials: true` 且 body 无凭证字段；login.page.vue 判空不含 refreshToken；`npm run build` 通过。
- **R3 红**：gateway application.yml allowedOrigins 为环境变量占位 `${CORS_ALLOWED_ORIGINS:...}` 且默认回退两个本地开发地址。

## 4. §6 门禁留证

1. **offline 全量**：`--mode=offline test` rc=**0**、BUILD SUCCESS；逐位 common **52** / gateway **50** / user **152** / record **157** / verify **159** / leaderboard **70** / mapmatch **15**（=**655**）。user 基线 147 → 152 净增 **+5**（AuthControllerTest 新增 5 例）；其余六模块逐位不变。
2. **契约门双态**：在途 `--open TASK-205 --baseline=206ea6c` rc=**0**；handoff 建立后判据 B `--baseline=206ea6c` rc=**0**；收口无参 rc=**0**。
3. **token**：29 项 tracked `git grep -cF` 全量 SUM 开工=**2030**、C-01 后=**2030**、C-02 后=**2030**（只增不减；本课题新文档不枚举保护 token 字面量）。
4. **词面门**：CI-exact `-n -I -iE` 对改动文件集 ZERO_HIT（rc=1）；合成探针阳性对照（CI-exact 形态命中 rc=0、BRE 形态无 `-E` 漏检 rc=1）证明 `-E` 缺失会假阴性、CI-exact 口径有效。
5. **`git diff --check`** rc=**0**（含 staged；C-01/C-02 两笔 `git show --check` 均 rc=0）。
6. **行尾**：改 7 文件保持各自基线——AuthController.java / application.yml / README.md 为 CRLF，token.ts / client.ts / login.page.vue 为 LF（基线各自，未 mixed 化）；新 AuthControllerTest.java 与 handoff.md 纯 LF+末尾换行、无 BOM。
7. **§0 零触碰面**：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / sql / 父 pom / api 模块（TokenDTO/RefreshRequestDTO/LogoutRequestDTO 等零改动）/ AuthService / InternalAuthController / common 主源码 / verify·leaderboard·mapmatch·record 四服务 / gateway-service 除 application.yml allowedOrigins 一处外 / web 除 §4 白名单 3 文件外（main.ts / notificationWs.ts / 路由 / 组件）——均无改动。
8. **web 验证**：本机 `npm run build`（含 type-check）**类型校验 vue-tsc rc=0；`npm run build`（vite build）本机失败，但非代码问题（3283 modules 全数 transpile 通过后，esbuild 在清理 `$TEMP\esbuild-*` 临时目录时触发 Windows `Access is denied`，重试复现，属环境 ACL 问题）——按任务书 §6 登记「CI web job 为权威，外部终验覆盖」**。

## 5. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工 **2030**、C-01 后 **2030**、C-02 后 **2030**（只增不减）。本课题新文档（tasks.json / spec §7 / handoff.md / PLAN 追加）未引入受保护 token 字面量；C-01 的 7 个实现/测试/配置文件对 29 项 token 命中 **0**。

## 6. 实现要点

- **R1（AuthController cookie 通道，唯一 Java 生产改动）**：集中常量 `REFRESH_COOKIE=refresh_token`、`COOKIE_PATH=/api/auth`；login/refresh 成功后以 `ResponseCookie`（HttpOnly / SameSite=Lax / Path=/api/auth / maxAge=refresh 存活 TTL 秒数）经 `response.addHeader(SET_COOKIE,...)` 下发，随后 `tokenDTO.setRefreshToken(null)` 再返回（body 不泄漏）。refresh/logout 读值 `request.getCookies()` 遍历同名项 cookie 优先、`dto.getRefreshToken()` body 兜底；logout 再追加 `maxAge(0)` 清除。maxAge 秒数取 `@Value("${app.auth.jwt.refresh-ttl-days:7}")` × 86400 与 AuthService/JwtUtil 同源（refreshTtlSeconds）；零值场景无 cookie 且 body 空时以 null 走 authService 既有 401 裁决。**含一处必要放宽**：RefreshRequestDTO/LogoutRequestDTO 的 refreshToken 为 `@NotBlank`，而 cookie 通道下 body 常空，若保留 `@Valid` 会抢先 400 阻断 cookie 读取，故对 refresh/logout 参数移除 `@Valid` 并放宽 `@RequestBody(required=false)`（DTO 本体零改动；空值语义交 AuthService 既有裁决）。BizException 局部 handler **零改动**。
- **R2（前端三文件）**：token.ts 删 `REFRESH` 键与 `getRefreshToken`，`setTokens(accessToken, role)` 两参，`clearTokens` 保留对旧 `refresh_token` 键一次性 removeItem（存量清理）；client.ts 删「本地无 refresh 才失败」前置块，refresh 改 `api.post('/api/auth/refresh', {}, { withCredentials: true })`，成功分支 `setTokens(t.accessToken, t.role || '')`，`_retry` 与 auth 端点豁免零改动；login.page.vue 判空去 refreshToken、`setTokens(t.accessToken, t.role)`。
- **R3（网关 CORS + README）**：application.yml `allowedOrigins` 整段替换为 `${CORS_ALLOWED_ORIGINS:http://127.0.0.1:5173,http://localhost:5173}`（逗号分隔 → List 绑定，其余 CORS 项零改动）；README 鉴权章节补 refresh HttpOnly cookie 下发口径（SameSite=Lax / Path=/api/auth / access 仍 Bearer 的边界）与 CORS `CORS_ALLOWED_ORIGINS` 注入说明。

## 7. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身提交哈希，沿 TASK-178/183/204 同型先例，以 C-02 实际提交哈希为准。
- **D2（@Valid 放宽，设计内必要调整）**：见 §6 R1——refresh/logout 的 `@Valid`（DTO 侧 `@NotBlank` 与 cookie 通道的 body 空冲突）移除并放宽 `required=false`，DTO 本体零改动，空值语义仍由 AuthService 既有 401/幂等裁决，未扩大改动面。
- 停止条件 1（需触零触碰面）/ 2（offline 既有用例红且非本课题）/ 3（web build 红需超白名单）/ 4（gateway 因 CORS 红且白名单内不可修）/ 5（cookie 与网关白名单/CORS 计划外交互）/ 6（需真实跨域/容器联调）——**均未触发**（真实跨进程 cookie 链路登记未覆盖，见 §8）。

## 8. 未覆盖与不得推出的结论

- **真实跨进程/跨服务联调**（浏览器实际带 cookie → 网关 → AuthController → 服务 401 链路）未跑；`--mode=online` 与 CI 未跑。
- **未达外部门槛**：未 push、未建 PR，无 CI run；CI 外部终验待推送后由指导侧补记。
- **web build**：本机 `vue-tsc type-check` rc=0（全部 TS/Vue 类型正确）；`vite build` 因 esbuild 清理临时目录的环境级 `Access is denied` 无法完成，3283 modules 已全数 transpile——登记「CI web job 为权威，外部终验覆盖」。。
- 本项目前端无单测框架（web/package.json 仅 build 脚本），R2 以 vite build + 文件实证为权威，未跑浏览器端 cookie 行为。

## 9. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**（收口后回传文件已入库，判据 A 放行）。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**。
- `git diff --check` 与两笔 `git show --check` 均 rc=0。
- `git diff --name-only` 对照：C-01 恰 7 条（= §4 白名单）、C-02 恰 4 条（tasks.json / spec.md §7 / handoff.md / PLAN.md）。
- 两笔 commit hash：C-01 `6d0318d`；C-02 为本 handoff 所在提交。
