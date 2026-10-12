# TASK-205 任务书：web 凭证面与 CORS 生产姿态（refresh cookie 化 + 前端适配 + CORS 环境变量化）

> 派发基线：本任务书所在派发笔（见 §6 契约门 --baseline 参数）。开工前 `git status --porcelain` 确认干净。

## §0 红线（零触碰面，越界即停手回报）

- ci.yml、scripts/verify/mvn-verify.sh、mailbox-contract.sh、docker-compose*、.trivyignore
- user-service：AuthService.java、InternalAuthController.java、application.yml、pom.xml
- api 模块全部（TokenDTO/RefreshRequestDTO/LogoutRequestDTO/LoginRequestDTO 等零改动——半径控制核心）
- common 主源码；verify/leaderboard/mapmatch/record 四服务全部；父 pom；sql/
- gateway-service：除 application.yml 的 globalcors.allowedOrigins 一处外零改动（禁碰路由/白名单/密钥/鉴权配置）
- web/src：除 §4 白名单 3 文件外零改动（main.ts/路由/组件零改动）
- 归档、.codex/、.trae/、work/mailbox 既有内容（PLAN.md 纯追加除外）

## §1 侦察实证（指导侧 2026-10-12 亲核）

| # | 位置 | 现状 |
|---|---|---|
| 1 | web/src/utils/token.ts L5-L33 | access/refresh/role 三键 localStorage；refresh 7 天存活持久化，XSS 可读 |
| 2 | web/src/api/client.ts L131-L149 | 401 单次 refresh：localStorage 读 rt → body 传 /api/auth/refresh → setTokens 轮换 |
| 3 | web/src/api/notificationWs.ts L24-L62 | STOMP CONNECT 用 Bearer 头（access 必须 JS 可读；全量 cookie 化不可行的硬约束） |
| 4 | web/src/pages/login.page.vue L63-L64 | 登录成功判空含 t.refreshToken；setTokens 三参 |
| 5 | user-service AuthController L52-L78 | login/refresh body 下发 refreshToken；logout body 收值；L108-L115 BizException→401 局部处理既有 |
| 6 | api 模块 TokenDTO | accessToken/refreshToken/role/tokenType/expiresIn 五字段（零改动） |
| 7 | gateway application.yml L77-L95 | allowedOrigins 硬编码 127.0.0.1:5173/localhost:5173；allowCredentials=true；allowedHeaders/exposedHeaders=* |
| 8 | 全仓 | /internal/auth 无 Feign 调用方（治理面工具端点，body 通道保留） |
| 9 | web/package.json | 仅 build 脚本无前端测试框架（前端验证以 vite build 为权威） |

## §2 预注册设计

### R1 AuthController cookie 通道（唯一 Java 生产改动文件）
- 常量：cookie 名 refresh_token、Path=/api/auth（与对外面端点前缀一致）。
- login：authService.login 返回 TokenDTO 后——
  1) `ResponseCookie` 构造（值=refreshToken；httpOnly(true)；sameSite("Lax")；path(/api/auth)；maxAge=refresh 存活 TTL 秒数——取 AuthService 侧同源配置/常量，若为字面量则同步同值并注释锚定）；
  2) response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())；
  3) tokenDTO.setRefreshToken(null) 后返回 Result（body 不泄漏）。
- refresh：读值 cookie 优先（request.getCookies() 遍历同名项）、RefreshRequestDTO.getRefreshToken() 兜底；
  取到的值走 authService.refresh(...)；成功后同 login 步骤 1-3（新值 Set-Cookie + body 置 null）。
  无任何通道提供值时：以 null/空值走 authService.refresh，由其既有 401 语义裁决（不新增前置校验）。
- logout：读值 cookie 优先、body 兜底 → authService.logout(值)（幂等语义既有）；响应追加
  Set-Cookie（同名；maxAge(0)；同 path；httpOnly/sameSite 保持一致）清除浏览器残留。
- BizException 局部 handler（L108-L115）零改动（401 场景不清 cookie，保持最小面）。
- 禁止：把 cookie 名/属性散落多处（集中常量）；引入新依赖（ResponseCookie 属 spring-web 既有）。

### R2 前端三文件
- token.ts：TOKEN_KEYS 删 REFRESH；getRefreshToken 删除；setTokens(accessToken, role) 两参；
  clearTokens 保留对旧 refresh 键 removeItem（存量清理）+ 其余两键照旧；hasAccessToken/triggerAuthFail/authFailHandler 零改动。
- client.ts：L131-L149 refresh 段——删除 `const rt = getRefreshToken(); if (!rt) {...}` 前置块
  （cookie 可见性 JS 不可知，统一由服务端 401 裁决）；refresh 调用改
  `api.post('/api/auth/refresh', {}, { withCredentials: true })`；成功分支 setTokens(t.accessToken, t.role || '')；
  _retry 防循环与 auth 端点豁免清单（L123）零改动。
- login.page.vue：L63 判空改 `t && t.accessToken && t.role`；L64 setTokens(t.accessToken, t.role)。

### R3 网关 CORS
- application.yml globalcors allowedOrigins 整段替换为环境变量占位单行：
  `${CORS_ALLOWED_ORIGINS:http://127.0.0.1:5173,http://localhost:5173}`（逗号分隔→List 绑定）；
  其余 CORS 项（methods/headers/credentials/exposedHeaders/maxAge）零改动。
- README：鉴权章节补 refresh 经 HttpOnly cookie 下发说明（含 SameSite=Lax/Path 口径与 access 仍 Bearer 的边界）；
  CORS 处补 CORS_ALLOWED_ORIGINS 注入说明（本地默认零扰动）。README 既有行尾保持。

### AuthControllerTest（新建，纯 JVM Mockito + MockHttpServletRequest/Response）
- 风格对齐 AuthServiceTest（同包 test 目录）；不启 Spring 上下文、无网络/中间件。

## §2.5 红绿判别式（全量落地，先红后绿留证）

- R1a 红：login 成功 → 响应 Set-Cookie 含 refresh_token=<值>、HttpOnly、SameSite=Lax、Path=/api/auth；
  且返回 body 的 data.refreshToken == null。
- R1b 红：refresh 请求 cookie 携旧值、body 携空/异值 → authService.refresh 收到的是 cookie 值（cookie 优先）；
  成功响应新 Set-Cookie + body 置 null。
- R1c 红：logout 请求 cookie 携值 → authService.logout 收到 cookie 值；响应 Set-Cookie 同名 maxAge=0。
- R1d 红：refresh/logout 仅 body 携值（无 cookie）→ 收到 body 值（兜底通道）。
- R2 红：token.ts 无 refresh setItem；client.ts refresh 调用含 withCredentials 且 body 无凭证字段；
  login.page.vue 判空不含 refreshToken；vite build 通过。
- R3 红：gateway application.yml allowedOrigins 为环境变量占位且默认回退两本地地址（配置文件实证 + compose config 默认态零扰动）。
- G1 绿：零触碰面 diff 全空（InternalAuthController/AuthService/api 模块逐字不动）。
- G2 绿：AuthServiceTest 全量不回归（服务层零改动的直接证据）。
- G3 绿：STOMP/notificationWs/main.ts 零改动（access Bearer 链路不受影响）。

## §3 停止条件（触发即停手回报归因，禁自行扩大改动面）

1. 需触碰 §0 任意零触碰面才能推进。
2. offline 既有用例红且归因不在本课题改动（或无法定位）。
3. web vite build 红（TS 类型错等）且修复需超出 3 文件白名单。
4. compose config / 网关既有测试（gateway 50 用例）因 CORS 改动红且无法在白名单内修复。
5. cookie 与网关白名单/CORS 产生计划外交互（如 Set-Cookie 被网关剥离）——登记实证后停手。
6. 出现需真实跨域/容器联调才能验证的项——登记未覆盖，不擅自起环境。

## §4 白名单（C-01 恰 7 文件，与 git diff --name-only 双向全等）

1. user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
2. user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java（新建）
3. gateway-service/src/main/resources/application.yml
4. web/src/utils/token.ts
5. web/src/api/client.ts
6. web/src/pages/login.page.vue
7. README.md

## §6 门禁（全量留证）

- offline 全量（唯一入口 scripts/verify/mvn-verify.sh，经 D:\git\Git\bin\bash.exe，退出码 3 不得记 PASS）：
  逐位对照 650=52/50/147/157/159/70/15，仅 user 净增（AuthControllerTest 新增数），其余六模块逐位不变。
- 契约门双态：在途 --open TASK-205 --baseline=<派发笔哈希>；收口无参。
- token 29 项 tracked git grep -cF SUM=2030 只增不减（新文档禁 token 字面量与字面正则模式）。
- 词面门：CI-exact 口径（-n -I -iE，注意本机须显式 -E 否则竖线退化为字面量致假阴性），改动文件集 ZERO_HIT +
  合成探针阳性对照。
- git diff --check 干净；行尾：改文件保持基线各自身（执行前自查每个文件的基线行尾，新文件纯 LF+末尾换行）。
- web 验证：本机可跑 npm run build 则跑并留证；否则登记「CI web job 为权威，外部终验覆盖」。
- MSYS sed -i 禁对 tracked 文件；不用 git add -A；commit msg 经 .trae/tmp + [IO.File]::WriteAllText(UTF8Encoding($false)) 无 BOM + git commit -F。

## §7 回填（C-02 台账笔，执行侧完成后填写）

- 两笔提交哈希：C-01 = 6d0318d；C-02 = 本台账笔所在提交（D1 自指哈希，以实际提交为准）
- offline 终值：650 → **655**（user 147 → **152** 净增 +5，其余六模块 52/50/157/159/70/15 逐位不变确认）
- token 终值：2030 → **2030**（只增不减，开工/C-01/收口三时点均 2030）
- 红绿判别式逐条结果：R1a/R1b/R1c/R1d = **PASS**（AuthControllerTest 新增 5 例全绿）；R2/PASS、R3/PASS、G1/G2/G3 = PASS
- 只改清单实证：C-01 git diff --name-only 恰 7 文件全等；C-02 恰 4 文件（tasks.json/spec.md/handoff.md/PLAN.md）
- 偏差与未覆盖登记：见 handoff.md §7/§8——D1 C-02 自指哈希沿先例；D2 refresh/logout 的 @Valid（DTO @NotBlank 与 cookie 空 body 冲突）移除并放宽 required=false，DTO 本体零改动、空值语义仍交 AuthService 既有 401/幂等裁决；web vite build 本机因 esbuild 清理临时目录的环境级 Access denied 无法完成（3283 modules 已全数 transpile、vue-tsc rc=0），按 §6 登记「CI web job 为权威，外部终验覆盖」；真实跨进程 cookie 链路未跑登记未覆盖。
- 补记（指导侧封盘笔，2026-10-12）：推送 947b231..66e7f01（4 笔）后第 45 次 CI（run 38204317451，
  head=66e7f01）conclusion=success 全 job 绿（build：offline 655 + 镜像 + Trivy + 词面门 + compose config；
  web：build/type-check——N2 本机 esbuild 环境受阻项经 CI web job 权威验证补齐）。外部终验达成，TASK-205 封盘。
  四环节终裁全过（指导亲核/独立复核五块 PASS 零阻断/两报告零矛盾/CI success）；非阻断项 N1（真实跨进程/跨域
  cookie 链路）登记未覆盖；D2（refresh/logout @Valid 移除+required=false）裁决为设计内必要调整。
