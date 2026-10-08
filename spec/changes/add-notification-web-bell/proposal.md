# 提案：add-notification-web-bell（Web 通知铃铛与通知列表页）

提案人：指导 Agent（2026-10-08）。状态：待派发。

## Why（为什么做）

TASK-182 通知中心后端已闭环（notification 表 / 判定事件消费者 / 四个 REST 接口），但 Web 控制台零通知入口：收件人唯一获知方式仍是轮询各业务列表，社交闭环的「最后一公里」断在前端。全仓现状核实：

- `web/src` 零通知代码（`notification|notif|铃铛|未读` 检索零命中）；
- 后端契约现成且冻结：`GET /user/api/notifications`（分页，id 倒序）、`GET /user/api/notifications/unread-count`、`PATCH /user/api/notifications/{id}/read`（5003 = 不存在/已读/非本人）、`PATCH /user/api/notifications/read-all`（返回流转条数）——四接口对本提案**够用，零后端改动**；
- 前端既有内规可直接复用：Bearer 鉴权走 `web/src/api/client.ts` 统一封装（网关注入 X-User-Id，前端不传 userId，先例 friends / leaderboard / record）；页面范式沿 `friends.page.vue`（错误码直接展示、需登录会话）；顶栏导航在 `App.vue`（a-layout-header 按钮列）。

## What（做什么）

在 web 控制台新增通知读取面（前端全量）：

1. **API 封装**（`web/src/api/client.ts` 纯追加）：`NotificationViewDTO` 前端类型（与后端 `NotificationView` 逐字段对齐：id / type / sourceId / title / content / isRead / createdAt / readAt）+ 四个调用函数（分页列表 / 未读数 / 单条已读 / 全部已读），均走 `/user/api/notifications` 前缀、不传 userId；
2. **顶栏铃铛**（`App.vue` 顶栏追加）：a-badge 未读数徽标 + BellOutlined 图标（`@ant-design/icons-vue` 既有依赖，零新增 npm 包），点击跳 `/notifications`；未读数经共享 composable 维护——登录态挂载即拉取、60 秒轻量轮询、页面可见性恢复时刷新；
3. **通知列表页**（`web/src/pages/notifications.page.vue` 新增，沿 friends.page.vue 范式）：分页表格（类型 / 标题 / sourceId / 已读 / 时间列）+ 单条已读 + 全部已读（展示流转条数）+ 刷新；未登录与 401/403 沿前端既有会话处理内规；
4. **路由类型再生成**：新增页面触发 `typed-router.d.ts` 重新生成，生成物入库提交（工程惯例：tracked 生成物，增删页面后必须提交，CI web 档有一致性检查）。

## 明确不做（本变更边界）

- **零后端改动**：Java 侧六服务 `src/**`、api 模块、sql 脚本、`pom.xml`、`ci.yml`、`scripts/` 全部零触碰（TASK-182 契约已冻结）；
- **零新增 npm 依赖**：`pnpm-lock.yaml` 不动（图标库 `@ant-design/icons-vue` 已在 dependencies）；
- **实时推送（WebSocket / SSE）不做**：铃铛新鲜度由 60 秒轮询承载；渠道升级沿 TASK-182 提案「推送渠道扩展另行提案」边界；
- **通知设置 / 偏好 / 免打扰**：不在范围；
- **点赞通知、排榜变动通知**：仍属后端二期候选，本提案只消费既有三类通知（RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED）。

## 依赖增量与红线核查

- 仅改 `web/` 四文件：`web/src/api/client.ts`（纯追加）、`web/src/App.vue`（顶栏追加铃铛）、`web/src/pages/notifications.page.vue`（新增）、`web/src/composables/useNotificationBell.ts`（新增，模块级共享 ref）；外加生成物 `web/src/typed-router.d.ts`；
- Java 侧零改动 ⇒ 离线测试基线 480 逐位不动、`--static=record-service` 811 不动（只验不增，无需重跑生产门禁全量——收口以 web 门禁为主、Java 门禁抽验不回归）；
- 验收门 = CI web 档三件套本地预演：`pnpm --dir web type-check` rc=0 + `pnpm --dir web build` rc=0 + `typed-router.d.ts` 与提交一致（web 无单测框架，类型检查与构建即前端质量门，如实登记不虚构单测数）。

## 验收要点

- 铃铛未读数与 `/unread-count` 读数一致；通知页单条已读 / 全部已读操作后未读数即时同步（共享 composable）；
- 未登录不发起通知请求（挂载即判 `hasAccessToken()`）；401 / 403 沿 client.ts 既有 refresh 与会话失败处理，前端不伪造身份；
- 5003（不存在 / 已读 / 非本人）等业务错误码直接展示（沿 friends 页内规）；
- `type=RECORD_VERIFIED` 通知可凭 sourceId 对应 recordId（文案不携带判定细节，细节在业务页看，沿 TASK-182 基调）；
- 契约门两件套 / 词面门 / 受保护 token 29 项只增不减 / PLAN.md 纯追加，惯例照旧；
- 外部终验：推送后下一次外部门槛 CI 绿（web 档 + build 档双绿）。

## 风险

- **未读数跨组件同步** → 缓解：模块级共享 ref（composable 单例），App.vue 与通知页 import 同一实例，不引入状态库；
- **轮询打扰** → 缓解：60 秒间隔 + 仅登录态 + 页面不可见时暂停（visibilitychange 控制），后端未读数走 COUNT + 索引（TASK-182 已保证查询代价）；
- **typed-router.d.ts 漂移** → 缓解：build 后立即入库生成物，CI web 档一致性检查兜底。
