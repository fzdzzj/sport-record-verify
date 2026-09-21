# Web 控制台脚手架

Vue 3 + Vite + TypeScript + Ant Design Vue 4 + Tailwind 4 + Axios + @tanstack/vue-query + vue-router + unplugin-vue-router（基于 `.page.vue` 文件路由）。

## 启动

```bash
cd web
pnpm install
pnpm dev
```

默认端口 5173。浏览器访问 http://localhost:5173

开发代理已配置，将 `/api`、`/user`、`/record`、`/leaderboard`、`/verify`、`/admin`、`/mapmatch` 原样转发到 `http://127.0.0.1:8080`（changeOrigin=true，无 rewrite 剥前缀）。

## 验证（门槛上跑什么）

CI 的 `web` job 对 `web/` 执行四条命令，本地改完前端请跑同样四条再提交：

```bash
cd web
pnpm install --frozen-lockfile   # 依赖声明与 pnpm-lock.yaml 漂移时这一步直接失败
pnpm type-check                  # vue-tsc --noEmit，类型错误即门槛失败
pnpm build                       # vite build 生产构建
git diff --exit-code -- src/typed-router.d.ts   # 非 0 = 提交的生成物与当前路由表不同步
```

- `package.json` 既没有 `packageManager` 也没有 `engines` 字段 → CI 上 pnpm 版本由门槛文件显式提供
  （`corepack prepare pnpm@10.25.0`）；本地用哪个版本自便，但门槛按该版本判定。
- 改了依赖声明就必须把 `pnpm-lock.yaml` 一起提交，否则 `--frozen-lockfile` 当场红，
  失败信号指向锁文件而不是业务代码。
- `src/typed-router.d.ts` 是 unplugin-vue-router 的生成物且被跟踪（提交它，IDE 与 type-check 才不必先跑
  codegen）。增删 `src/pages/*.page.vue` 后 `pnpm build` 会重写它——**把重新生成的结果一并提交**即可，
  最后一条命令就是防它不同步的（实测连续两次构建产物逐字节一致，判据不抖）。
- `vue-router/auto` 的类型由 `src/vue-router-auto-shim.d.ts` 手写提供：vue-router@4.6 把该子路径的类型入口
  留成空占位（`node_modules/vue-router/vue-router-auto.d.ts` 仅 36 字节一行注释），而本仓用的
  unplugin-vue-router@0.19.2 不改写它；缺该垫片时 `pnpm type-check` 报 8 条 TS2306 + 3 条 TS7006。
  垫片只补类型，不改运行时（`createRouter` 仍来自插件的 `vue-router/auto` 别名）。
- 后端联调前提（起网关与各服务、`app.auth.enabled=true`）见下节，与这四条命令无关。

## 认证会话（add-web-auth-session）

- 注册页：`/register` ，POST /api/auth/register ，成功不返回 token，提示去登录；重复手机号 2001。
- 登录页：`/login` ，POST /api/auth/login ，成功保存 accessToken/refreshToken/role（分别存储），进入控制台。
- 401 单次 refresh：业务请求 401 时，用 refreshToken 调用 /api/auth/refresh 一次，成功更新 token 并重试；失败清存储跳登录（防死循环）。
- 路由守卫：无 accessToken 不能进入控制台（/ 等）；已登录访问 /login 自动跳转控制台；退出清 localStorage。
- 请求头：仅 `Authorization: Bearer <accessToken>` ；**不发送** headers.token、X-User-Id、X-Role（由网关注入）。

**演示须开启鉴权**：
- 启动网关及各服务（user-service 等）时，**必须设置 `app.auth.enabled=true`**（可通过环境变量、启动参数或本地 yml 覆盖）。
- 仓库默认配置仍为 `false`（兼容压测脚本裸调用），**本变更及前端演示文档均不修改任何 Java 默认配置文件**。
- 开启后，前端带 Bearer 的请求会被网关校验；关闭时多带头可被忽略（向下兼容）。
- 登录/注册/刷新走白名单 `/api/auth/**` ，不依赖鉴权开关。

## 探活

- 登录页：真实对接登录。
- 探活页：点击按钮调用 `GET /leaderboard/api/leaderboard?type=overall&size=2`，展示 `{code, message}` 解析结果。

Axios 统一响应处理：`code === 0` 成功，否则抛出 `message`。

## 注意

- 仅脚手架。业务功能（记录提交、榜单、管理端入口等）在后续独立变更中实现。本变更仅实现认证会话与守卫。
- 后端网关需运行在 8080 端口以测试代理。
- 包管理：pnpm。
- 不包含 Vuex / ECharts / OpenAPI 生成 / Playwright。
- 参考技术栈选型来自同类工程，但代理与响应码严格按本仓规范（无 rewrite、code=0）。

**口径自检**：已按 spec-delta 实现；未改 Java app.auth.enabled 默认；token 分别存储；仅 Bearer；README 强调演示开启鉴权。

## 业务演示（add-web-record-console）

**前提**：网关 + record-service + user-service + leaderboard-service + verify-service 运行中，**app.auth.enabled=true**（演示必须）。

启动前端：
```bash
cd web
pnpm install   # 如未装
pnpm dev
```
访问 http://localhost:5173

**演示顺序**（登录后）：
1. 注册/登录（已有账号直接登录）。登录后顶部出现业务导航。
2. 点击「提交记录」：
   - 默认加载内置样例轨迹（TrackPointDTO 数组：seq/lat/lng/ts，10 点真实路网）。
   - 选 sportType，自动生成 requestId。
   - 点击提交 → POST /record/api/records 。
   - 结果展示 recordId、status（0=SUBMITTED）；点击「保存到本会话」。
3. 点击「判定/申诉/点赞」或从提交页跳转：
   - 输入/从会话加载 recordId。
   - 开始轮询 GET /record/api/records/{id}/verify-result 。
   - **verdict=0**：显示「校验中」，继续每 3s 轮询，**不显示失败**。
   - verdict=1 (PASSED)：显示通过 + score；下方可点赞/取消（POST/DELETE /record/api/records/{id}/like），显示 likeCount/liked。
   - verdict=2 (REJECTED)：显示拒绝；可填理由提交申诉 POST /.../appeal 。
4. 点赞测试：通过记录点赞成功；故意对未通过记录点赞 → 捕获 6001 错误并展示「未通过校验的记录不可点赞」，**不当作成功**。
5. 「好友」页：
   - 输入 targetUserId（可从榜单或手动提取 myUserId：提交后在提交页点「提取我的 userId」从 /points 取）。
   - 发起申请 → 返回 request id + status。
   - 用 request id 同意/拒绝。
   - 刷新列表 GET /user/api/friends （仅 accepted）。
   - 错误如 5001 直接展示 message。
6. 「榜单」页：
   - 切换 overall / friend ，查询 GET /leaderboard/api/leaderboard?type=... 。
   - 展示 rank/nickname/distance；friend 榜空时正常空列表。
7. 退出登录测试守卫。

**无服务端「我的记录」列表限制**（诚实说明）：
- 本变更**未使用/假装有**后端记录列表 API（record-service 暂无公开 /my-records 等）。
- 提交页用 `sessionStorage` 记住**本次浏览器会话**内提交产生的 recordId 列表。
- 刷新页面、关闭标签、换浏览器、清除存储后，记录 id 丢失，需重新提交或手动记下 id 再输入。
- 演示时建议在同一会话内完成链路；README/页内均标注此限制。
- 未来若加服务端列表，将另开变更。

**已验证约束**：
- 内置样例为 TrackPointDTO 数组，非 OSM 原始。
- 提交等不伪造 userId（auth 下由网关 X-User-Id）。
- 仅 web/ 页面 + API 封装 + README + tasks.json。
- 无新 Java API、无管理端、无 ECharts/地图。

**口径自检**：按 proposal 实现；tasks.json 已打勾；最多 1 次修复；未改 proposal/spec-delta。

## 治理面控制台（add-web-admin-console）

**前提**：网关 + verify-service + user-service 等运行，**app.auth.enabled=true**（演示必须）。有 ADMIN 角色用户（**前端不暴露 grant-admin 页面**）。

登录后，顶部导航仅 role=ADMIN 时显示「规则版本」「申诉终判」（高亮 cyan）。

**演示**：
- 点击「规则版本」：
  - 创建版本：输入 version/grayRatio（可选 rules JSON 快照）；POST /verify/rules/versions 。
  - 调灰度：输入 id + grayRatio；PATCH /verify/rules/versions/{id}/gray （0=回滚）。
  - 全量：输入 id；POST /verify/rules/versions/{id}/activate 。
  - 展示返回 version 信息；4002/4003/4004 错误显示 `[code] message`。
- 点击「申诉终判」：
  - 手工输入 appealId（业务申诉后记下 id）。
  - 输入 operator（如 admin）、recheckResult。
  - 按钮「终判通过」或「维持拒绝」 → POST /admin/api/appeals/{id}/review {operator, pass, recheckResult}。
  - 展示返回 appeal 状态。

**403 展示**：非 ADMIN 打开 /admin-rules 或 /admin-appeal 必须看到 403 错误提示卡片，不渲染表单、不伪造成功数据。请求仍走网关，普通用户 token 得 403/1002。

**ADMIN 授予说明**：
ADMIN 授予仍走内部凭证接口，前端不暴露、不提供 grant-admin 页面或按钮。
示例（内部接口，网关通常不路由 /internal/**）：
```
curl -X POST http://127.0.0.1:8080/internal/auth/grant-admin \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: <your-internal-token>" \
  -d '{"userId": 123}'
```
授予后，用户需重新登录以获取带 `role: "ADMIN"` 的 token。登录响应中 role 由后端签发并存 localStorage（依赖方向 2 的 role 存储）。

**已验证约束**：
- 仅 role=ADMIN 显示管理菜单；非管理员打开管理 URL 展示 403，不伪造。
- 终判页手工输入 appealId，无申诉列表 API。
- ADMIN 授予仅内部接口，前端不暴露。
- 依赖 role 存储；所有请求经网关，不绕过。
- 仅 web/ 管理页与菜单、README ADMIN 说明、tasks.json。

**口径自检**：按 proposal 实现；tasks.json 打勾；最多 1 次修复；未改 Java；未改 proposal。
