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

## 前端测试（Vitest 基线）

- 运行：`cd web && pnpm test`（即 `vitest run`，单次执行；happy-dom 环境；测试内显式
  `import { describe, it, expect, vi } from 'vitest'`，不开全局注入）。
- CI：web job 在 `Type check` 之后、`Build` 之前增加 `pnpm --dir web run test` 一步
  （见 `.github/workflows/ci.yml`）。
- 范围（第一阶段）：`src/utils/__tests__/token.test.ts`（凭证存储：键常量、setTokens 双键回环、
  clearTokens 清理含存量 `refresh_token` 键、auth-fail handler 注册/调用/异常吞没）与
  `src/api/__tests__/client.test.ts`（拦截器链：Bearer 注入有无、code=0 透传、code!=0 抛出、
  401 单次 refresh 重试含 `withCredentials`、`/api/auth/*` 401 零 refresh、refresh 失败触发 auth-fail）。
- 传输层以 axios adapter 注入（替换 `api.defaults.adapter`）模拟，不引入 axios-mock-adapter / msw；
  仅新增 devDeps `vitest` + `happy-dom`，无 @vue/test-utils；E2E 自 TASK-227 起由 Playwright 承载（见下文「E2E 真实浏览器验证」小节）。
- 组件测试后置理由：页面为路由 / axios / query 重度耦合，无轻量可挂载组件（组件测试另立变更）；
  E2E 采用 route-mock 模式本地执行、不入 CI（TASK-227 重议引入，见下文）。

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

- 控制台已承载多业务功能（记录提交/判定申诉点赞/好友/榜单/我的记录与统计/轨迹查看/动态/删除/筛选/运动目标/通知等，见下方各变更小节）；认证会话与路由守卫为基础层。
- 后端网关需运行在 8080 端口以测试代理。
- 包管理：pnpm。
- 不包含 Vuex / ECharts / OpenAPI 生成。
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

**服务端「我的记录」列表（TASK-211 起已落地）**：
- add-web-record-console 阶段依赖 sessionStorage 记本会话 recordId 的限制已被 **TASK-211「我的记录端点与个人统计面板」**解除：
  首页登录态仪表盘的「我的记录/个人统计」区现调用 `GET /record/api/records/my`（分页 size 上限 500；过滤联动见 TASK-224 小节），不再依赖 sessionStorage。
- 提交页「本会话记录」卡片仍保留 sessionStorage 快捷记法（提交后自动保存 recordId，便于快速回跳判定页），属页面内快捷方式，与服务端列表无关。

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
授予后，用户重新登录或 refresh 续签后生效（最长延迟=access 剩余时效），以获取带 `role: "ADMIN"` 的 token。登录响应中 role 由后端签发并存 localStorage（依赖方向 2 的 role 存储）。

**已验证约束**：
- 仅 role=ADMIN 显示管理菜单；非管理员打开管理 URL 展示 403，不伪造。
- 终判页手工输入 appealId，无申诉列表 API。
- ADMIN 授予仅内部接口，前端不暴露。
- 依赖 role 存储；所有请求经网关，不绕过。
- 仅 web/ 管理页与菜单、README ADMIN 说明、tasks.json。

**口径自检**：按 proposal 实现；tasks.json 打勾；最多 1 次修复；未改 Java；未改 proposal。

## 轨迹查看与 GPX 导出（add-gpx-export）

- 记录轨迹页 `/record-detail`（TASK-213）：单次拉取 points（size=500），SVG 归一化投影示意形状。
- 有轨迹点时可点击「导出 GPX」：纯前端生成 GPX 1.1（`web/src/utils/gpx.ts`，trkpt lat/lon 固定 6 位小数、`<time>` ISO8601、name XML 转义、空数组抛错），经 Blob(application/gpx+xml) + a[download] 下载为 `record-{id}.gpx`；零后端、零新依赖。
- 「配速趋势」区块（TASK-219）：轨迹 SVG 下方折线图，X=累计公里（`web/src/utils/pace.ts` 私有 haversine R=6371km 逐段累加）、Y=配速秒/公里（倒置，越快越靠上）；speed 缺失/为 0 或配速大于 60 分/公里按停顿断线；悬停显示距离+配速+时刻；区块在 points>1 且存在可绘配速点时渲染；配套 vitest 6 测（`pace.test.ts`）；零后端、零新依赖。

## 连续打卡（TASK-220）

- 个人仪表盘统计卡区新增「连续打卡」卡：调用 `GET /record/api/records/my/streak`（后端单列投影聚合，仅计入通过终态 PASSED/RE_PASSED，同日多条算一天，今日无记录从昨日续算），展示「当前 N 天 · 最长 M 天」；独立拉取，失败不拖垮统计卡区（显示 —）；后端零新依赖、零新 SQL 形态。

## 记录可见性三态（TASK-221）

- 提交页新增「可见性」三选 radio（公开=0 默认 / 好友可见=1 / 仅自己=2），随提交载荷 `visibility` 下发（缺省服务端回退 PUBLIC）；我的记录列表新增可见性列：仅自己/好友可见显示彩色 tag，公开显示「公开」。`client.ts` 的 `RecordSubmitDTO`/`RecordSummaryDTO` 类型同步加 `visibility?: number`。读取面授权在后端统一收敛（PRIVATE 仅归属人 / FRIENDS 仅好友 / PUBLIC 所有人）。

## 好友动态 feed（TASK-222）

- 新增 `/feed` 路由页与顶部导航「动态」入口：调用 `GET /record/api/records/feed` 分页拉取好友动态——本人任意可见性记录 + 好友 PUBLIC/FRIENDS 记录按创建时间倒序；卡片展示作者昵称（缺失回退 authorUserId）+ 可见性徽标 + 运动类型/距离/状态 + 相对时间，卡片链接到详情页；`client.ts` 新增 `FeedItemDTO` 类型与 `getFeed` 封装。auth 关闭降级态为 PUBLIC 全站时间线；陌生人不入 feed。

## 记录删除与撤回（TASK-223）

- 首页「最近记录」列表操作列新增「删除」操作：Popconfirm 二次确认弹窗（防误触）；
- 点击确认后调用 `DELETE /record/api/records/{id}`（`client.ts` 封装 `delRecord`，网关注入 `X-User-Id` 归属门拦截越权），删除成功后刷新个人记录列表与连续打卡统计；
- 仅归属人本人在「我的记录」中展示删除操作，feed 动态流卡片不放删除按钮；后端单事务级联清理恰四表（主记录 + 轨迹热表 + 轨迹归档表 + 点赞行）并 best-effort 清理 Redis 缓存。

## 我的记录筛选（TASK-224）

- 首页「我的记录」区块新增过滤条：运动类型下拉（跑步/骑行/步行）、审核状态下拉（8 态）、开始日期区间（DateRangePicker）；
- 任一筛选条件变更或重置自动重置页码为第 1 页；
- 调用扩展后的 `getMyRecords(page, size, filters)`（`web/src/api/client.ts`），后端在 `LambdaQueryWrapper` 中对 `sport_type`、`status`、`start_time` 进行 null-safe 动态组合（保留 `user_id` 归属隔离与 `created_at DESC` 排序）；
- 零 schema 变更、零新索引、api 模块零触碰。

## 周/月运动目标（TASK-225）

- 首页个人统计区新增「运动目标与进度」卡片：每项目标展示周期标签（周目标/月目标）、指标与达成情况（距离 km / 次数）、完成态徽标（已达成）及进度条（`a-progress`，0..100 整数百分比封顶）；
- 目标交互：右上角「设置目标」按钮弹出新建对话框，支持设置周期类型（周/月）、指标（距离/次数）与目标值；已创建目标支持行内「编辑」（调整目标值）与「删除」（Popconfirm 二次确认）；
- 数据链路：调用 `getMyGoals()`（GET /record/api/records/my/goals，后端 pull-on-read 计算当前周/月窗口内 PASSED 终态记录的实时聚合进度）、`upsertGoal(payload)`（POST /record/api/records/my/goals，基于 user_id + period_type + metric 联合唯一键的 upsert 语义）、`deleteGoal(id)`（DELETE /record/api/records/my/goals/{id}，owner 归属校验）；
- 记录删除（TASK-223）联动：删除记录后自动刷新目标进度，删除后该记录自然退出目标聚合范围。

## E2E 真实浏览器验证（TASK-227）

- 引入 **Playwright**（devDependency `@playwright/test` **1.64.0** 版本锁定；chromium 单浏览器，浏览器二进制落系统用户缓存 `%LOCALAPPDATA%\ms-playwright`，不入库）。重议背景：TASK-215 曾裁定不引入（当时前端债小），现 9 课题连续登记「真实浏览器交互未跑」且交互面扩大至 feed/删除/筛选/目标，重议通过。
- 运行：`cd web && pnpm e2e`（即 `playwright test`）。**webServer 自动执行 `pnpm build && pnpm preview --port 4179`**（生产构建产物），测试命中本地预览服务；证据（运行输出）与 trace 落 gitignored `.trae/tmp/e2e-results`。
- **route-mock 模式**：`page.route` 拦截 `/record/api/*` 与 `/user/api/*` 两个前缀返回固定夹具（`web/e2e/fixtures/`），并断言两前缀之外的 API 请求为零（`helpers.ts` 请求监视器）——零后端依赖、零全栈起服务、零真实网络请求。
- 8 条 spec（`web/e2e/*.spec.ts`）：① 登录跳转（守卫）② 记录列表渲染 ③ 提交表单校验与提交 ④ 筛选联动+重置页码 ⑤ 删除确认与刷新 ⑥ feed 卡片与分页 ⑦ 目标卡片与对话框 ⑧ 顶栏导航。
- **范围声明**：E2E 仅本地执行、**不入 CI**（ci.yml 零触碰）；「榜单」页交互未覆盖（`/leaderboard/api/*` 不在两前缀 mock 范围，避免真实网络请求，候选后续扩展）；真实后端全栈联调（--it 层）与多浏览器未覆盖，均登记 handoff。
