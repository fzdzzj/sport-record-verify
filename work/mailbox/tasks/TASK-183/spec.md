# 任务书：TASK-183 add-notification-web-bell（Web 通知铃铛与通知列表页）

派发：指导 Agent（2026-10-08）。派发笔（本任务书 + 提案三件套）由指导侧亲笔；执行侧代为入库后实施 C-01…C-02 两笔，不得改动派发笔内容。

## 0. 硬约束与红线

1. **改动面唯一**：仅 `web/` 五文件——`web/src/api/client.ts`（尾部纯追加）、`web/src/App.vue`（顶栏追加铃铛）、`web/src/pages/notifications.page.vue`（新增）、`web/src/composables/useNotificationBell.ts`（新增）、`web/src/typed-router.d.ts`（构建生成物更新入库）。Java 侧六服务 `src/**`、api 模块、`sql/`、root pom、各 `pom.xml`、`ci.yml`、`scripts/`、`docker-compose*.yml` 零触碰。
2. **零新增 npm 依赖**：`web/pnpm-lock.yaml` 与 `web/package.json` 不动（`@ant-design/icons-vue` 已在 dependencies）。
3. **前端鉴权内规**：全部通知请求不传 userId（身份由网关注入的 X-User-Id 认定，先例 friends / leaderboard / record——`client.ts` 既有注释「userId omitted when auth (gateway injects)」）；未登录（`hasAccessToken()` 为 false）不发起任何通知请求；Bearer 会话与 401 refresh 沿 `client.ts` 既有封装零改动。
4. **验收门 = web 三件套 + Java 不回归抽验**：`pnpm --dir web type-check` rc=0、`pnpm --dir web build` rc=0、`typed-router.d.ts` 与提交一致（web 无单测框架，如实登记不虚构单测数）；Java 侧抽验 offline 全量 480 逐位（`36/41/63/127/144/59/10`）与 `--static=record-service` 811 不增（本任务 Java 零改动，抽验只为证不回归）。
5. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件（Java 门禁）；web 命令走 pnpm；临时 `*.tmp` 用毕删。
6. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件且无 BOM（Write 工具先例）；主题串先写入台账、再逐字用作 `-F` 消息。
7. **token 纪律**：受保护 29 项只增不减（开工实测登记，收口 ≥ 开工值）；PLAN.md 纯追加。
8. **措辞纪律**：新增文档零禁词、零词面门正则字面量。
9. **停止条件**：发现需改后端契约 / api 模块 / `pnpm-lock.yaml` / `client.ts` 既有函数签名才能完成 ⇒ 停手回报；type-check 或 build 红且非本任务新增代码所致 ⇒ 停手回报；`App.vue` 现场与派发笔假设（a-layout-header 按钮列、`goTo` 导航函数）不符 ⇒ 停手回报。

## 1. 背景与史实（现状证据）

- **后端契约冻结**（TASK-182 已外部终验，run `37779966984` 绿）：`GET /user/api/notifications?page=&size=`（`Result<PageResult<NotificationView>>`，id 倒序）、`GET /user/api/notifications/unread-count`（`Result<Long>`）、`PATCH /user/api/notifications/{id}/read`（`Result<Void>`，失败 5003＝不存在/已读/非本人）、`PATCH /user/api/notifications/read-all`（`Result<Integer>`＝流转条数）；`NotificationView` 八字段：id / type（RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED）/ sourceId / title / content / isRead（0 未读 1 已读）/ createdAt / readAt。
- **前端内规证据**：`web/src/api/client.ts`——按服务前缀封装（`/user/api/...`）、不传 userId、401 单次 refresh 重试、auth 失败清会话跳登录；`web/src/pages/index.page.vue`——「演示要求：网关+服务 app.auth.enabled=true；前端始终带 Bearer；不伪造 userId」；`friends.page.vue`——错误码 `[code] message` 直接展示（a-alert）、需登录会话。
- **顶栏与路由**：`App.vue` 顶栏为 a-layout-header 内按钮列（`goTo(path)` 导航函数、`isAdmin` 按角色显隐先例）；文件路由 `pages/*.page.vue`（unplugin-vue-router），`typed-router.d.ts` 为 tracked 生成物，增删页面后必须再生成并提交（CI web 档有一致性检查）。
- **web 档 CI 门**：`pnpm --dir web install --frozen-lockfile` + `type-check` + `build` + `typed-router.d.ts` 与提交一致；web 无单测框架（package.json 无测试脚本）。

## 2. 实施设计（预注册）

### 2.1 API 封装（`client.ts` 尾部纯追加，不动既有行）

- `NotificationViewDTO` 前端类型：八字段与后端 `NotificationView` 逐字段对齐（`isRead: number`、`createdAt / readAt: string` 可空）。
- `listNotifications(page = 1, size = 20)` → `GET /user/api/notifications`；
- `getUnreadCount()` → `GET /user/api/notifications/unread-count`；
- `markNotificationRead(id: number)` → `PATCH /user/api/notifications/{id}/read`（5003 走 Result.failure，错误展示沿既有 catch 范式）；
- `markAllNotificationsRead()` → `PATCH /user/api/notifications/read-all`。

### 2.2 共享未读数 composable（`web/src/composables/useNotificationBell.ts` 新增）

- 模块级单例：`const unread = ref(0)` + `refreshUnread()`（`hasAccessToken()` 为 false 时置 0 并直接返回，不发请求；请求失败静默置 0 不弹错，铃铛不阻塞主流程）。
- 刷新策略（仅登录态）：`App.vue` 挂载时启动——首次拉取 + `setInterval` 60 秒 + `document.visibilitychange`（visible→刷新、hidden→跳过本轮 interval 回调）；组件卸载清 interval（App.vue 常驻则随会话存续，登出后置 0）。
- 同一实例供 `App.vue`（读 unread）与通知页（操作后调 `refreshUnread()`）共享——不引入状态库、不用事件总线。

### 2.3 顶栏铃铛（`App.vue` 顶栏追加，不动既有按钮）

- `a-badge :count="unread" :overflow-count="99"` 包 `BellOutlined` 图标按钮，点击 `goTo('/notifications')`；入口对未登录用户可见（引导登录），但未登录不展示徽标数值（unread 恒 0 且不请求）。

### 2.4 通知列表页（`notifications.page.vue` 新增，沿 `friends.page.vue` 范式）

- `a-card` 标题「通知中心」+ 说明行（接口与鉴权口径，沿 friends 页说明行风格）；
- `a-table`：列 id / 类型 / 标题 / sourceId / 已读 / 创建时间，行 key=id；`a-pagination`（page/size 受控，沿列表接口分页参数）；
- 单条已读：仅 `isRead === 0` 行展示「标已读」操作，成功后行内翻转 + `refreshUnread()`；失败 5003 以 `[code] message` 展示（a-alert），列表不变；
- 全部已读：成功展示「本次流转 N 条」并刷新列表与未读数；
- 刷新按钮：重拉当前页与未读数；
- 未登录：页面顶部展示引导登录提示（沿 index 页提示风格），不发请求、表格空态展示。

### 2.5 路由类型生成物

- `pnpm --dir web build` 后 `typed-router.d.ts` 重新生成（新增 `/notifications` 路由项），确认 diff 后随 C-01 一并入库。

### 2.6 三支判定

- **PASSED**：web 三件套全绿 + Java 抽验不回归（480 逐位 / 811 不增）+ 验收要点全达成（未读数与接口读数一致且操作后即时同步、未登录零请求、5003 直接展示、空列表正常态）。
- **FAILED**：实施引入 type-check / build 红，或 Java 基线漂移 ⇒ 回滚本任务全部改动、如实登记。
- **外部终验**：推送后下一次外部门槛（第 25 次）CI 绿（web 档 + build 档双绿）；红则按签名归因（不带预设，禁重试刷绿）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `8258d40`（TASK-182 补记登记笔）；`origin/main` = `ef9cd8d`，`origin/main...main` = `0 1`；派发笔入库后基线前移（`0 2`）。
- 离线基线 480（`36/41/63/127/144/59/10`）；静态基线 811。
- 最近外部门槛：run `37779966984`（HEAD `ef9cd8d`，**绿**，第 24 次——TASK-182 外部终验达成）。
- web 基线：type-check / build 双绿（第 24 次门槛 web 档 28s 全绿为参照）。

## 4. 白名单

- **派发笔（指导侧亲笔，执行侧只入库零改动）**：`spec/changes/add-notification-web-bell/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。主题：`docs(spec): 派发 TASK-183 通知 Web 铃铛提案与任务书`。
- **C-01 前端实现笔**：五文件（`client.ts` 纯追加 / `App.vue` 顶栏追加 / `useNotificationBell.ts` 新增 / `notifications.page.vue` 新增 / `typed-router.d.ts` 生成物）。主题：`feat(web): 通知铃铛与通知列表页接入通知中心（TASK-183）`。
- **C-02 台账笔**：`spec/changes/add-notification-web-bell/tasks.json` 闭环 + 本任务书 §7 收口记录纯追加 + `handoff.md` + `PLAN.md` 纯追加。主题：`docs(mailbox): 登记 TASK-183 前端验收与台账闭环（TASK-183）`。
- **禁触**：Java 侧一切（六服务 / api / sql / pom）、`web/package.json`、`web/pnpm-lock.yaml`、`ci.yml`、`scripts/`、`client.ts` 既有行（只许尾部追加新函数与新类型）。

## 5. 受保护 tokens 基线（29 项，开工实测登记，只增不减）

`13.4`、`18.0`、`73.93`、`68.8`、`6315`、`1.8612`、`3.3066`、`5.7056`、`9.408`、`36525962432`、`36586847965`、`36438897772`、`36399582548`、`36098038547`、`2806`、`598`、`36736221648`、`36808102571`、`36821040708`、`36845152965`、`36871294588`、`36880083885`、`36958994260`、`36976873215`、`36992632143`、`36995450125`、`37008317295`、`37021305016`、`37591580687`（TASK-182 收口参照值可自其 handoff §6 取；开工 `grep -cF` 实测为准）。第 24 次 run 号 `37769966984` 及本任务新 run 号均以文本登记，不扩受保护集合。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态；`git diff --check` rc=0；契约门在途 `--open TASK-183 --baseline=<派发笔哈希>`（rc=1 时按 TASK-181 §1.2.4 登记的 extract_claims 盲区口径逐任务核对自身判据 B 原文行）；token 29 项只增不减；新增文件纯 LF 末尾换行完整。
2. **收口门禁（C-02 后亲跑留证）**：web 三件套——`pnpm --dir web type-check` rc=0、`pnpm --dir web build` rc=0、`git status` 确认 `typed-router.d.ts` 无未入库漂移；Java 抽验——`bash scripts/verify/mvn-verify.sh --mode=offline test` 480 逐位、`--static=record-service` 811 不增；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（未登录零请求判据、未读数同步判据、5003 展示判据）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）。
4. **演示验收说明**：本任务全部为前端改动，功能演示需网关+服务 `app.auth.enabled=true` 且登录会话有效（index 页既有演示要求）；本地 auth=false 时通知查询返回空列表属既定降级行为（与 friends / leaderboard 同口径），登记为说明项不判失败。

## 7. 收口记录（执行侧 C-02 纯追加）

（空位：提交哈希、web 三件套读数、Java 抽验读数、token 前后读数、未覆盖项、三支裁决，由执行侧回填）
