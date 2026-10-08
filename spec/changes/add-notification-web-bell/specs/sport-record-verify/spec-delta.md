# spec-delta：add-notification-web-bell

变更 `add-notification-web-bell` 对主规格 `spec/specs/sport-record-verify/spec.md` 做以下 ADDED（新增「Web 通知读取面」，归并位置：通知读取与已读需求之后）。

## ADDED Requirement: Web 通知铃铛

**GIVEN** 用户处于登录态（持有有效 Bearer 会话）
**WHEN** 打开 Web 控制台任意页面
**THEN**
- 顶栏展示铃铛入口与未读数徽标，未读数取自既有 `GET /user/api/notifications/unread-count`（前端不传 userId，身份由网关注入的 X-User-Id 认定，先例 friends / leaderboard）；
- 未读数在挂载时拉取、每 60 秒轮询刷新、页面可见性恢复时刷新；页面不可见时暂停轮询；
- 铃铛点击导航至通知列表页（`/notifications`）。

**GIVEN** 用户未登录
**WHEN** 打开 Web 控制台
**THEN** 前端 SHALL NOT 发起任何通知请求，铃铛入口不展示未读数。

## ADDED Requirement: Web 通知列表页

**GIVEN** 用户处于登录态并打开通知列表页
**WHEN** 页面加载或手动刷新
**THEN** 分页展示本人通知（id 倒序），列含类型 / 标题 / 聚合根 ID / 已读位 / 创建时间；空列表正常展示。

**WHEN** 对某条未读通知执行「标已读」
**THEN** 调用既有 `PATCH /user/api/notifications/{id}/read`；成功后该行已读位翻转、未读数即时同步（与铃铛共享同一状态源）；失败（5003 = 不存在 / 已读 / 非本人）时错误码直接展示，列表状态不变。

**WHEN** 执行「全部已读」
**THEN** 调用既有 `PATCH /user/api/notifications/read-all`，展示本次流转条数并刷新列表与未读数。

## ADDED Requirement: Web 通知面不回归既有基线

**GIVEN** 本变更实施完成
**WHEN** 全量门禁执行
**THEN**
- Java 侧零改动：六服务 `src/**`、api 模块、`sql/`、root pom 零触碰；离线测试基线 480 逐位不动；`--static=record-service` 811 不增；
- web 零新增 npm 依赖（`pnpm-lock.yaml` 不动）；`pnpm --dir web type-check` 与 `pnpm --dir web build` 均 rc=0；`typed-router.d.ts` 与提交一致（生成物入库）；
- 词面门 / 契约门 / 受保护 token 29 项只增不减，惯例照旧。
