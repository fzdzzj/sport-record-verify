# 提案：Web 治理面控制台（规则版本与申诉终判）

## Why

治理面接口已存在（规则版本、`/admin/api/appeals/{id}/review`），但只能 curl。需要 ADMIN 角色的页面才能演示灰度与终判，且必须走网关角色校验，不能在前端「假装是管理员」。

本变更依赖登录会话已能保存 role。普通用户只隐藏入口；实际 403 仍由网关返回。

**背景**：
- 规则：`POST /verify/rules/versions`、`PATCH /verify/rules/versions/{id}/gray`、`POST /verify/rules/versions/{id}/activate`，需 ADMIN。
- 终判：`POST /admin/api/appeals/{id}/review`，需 ADMIN。
- 普通用户访问上述路径：403/1002；未登录 401/1001。

**当前状态**：无管理页。

**期望状态**：role=ADMIN 可见管理菜单；可创建规则版本、调灰度、全量发布；可对申诉 id 终判通过/维持拒绝。无申诉列表 API 则管理页提供 id 输入框，不假装有工单队列。

## What Changes

- 管理布局与菜单：非 ADMIN 不渲染入口；强行打开 URL 时展示网关 403 信息。
- 规则版本页：创建、调 grayRatio、activate。
- 终判页：输入 appealId，提交通过或拒绝原因。
- 不新增后端列表 API。

**明确不做**：完整 RBAC 矩阵；运营工单系统；改网关角色模型。

## Impact

### 受影响的规范
- ADDED「Web 治理面控制台」。

### 受影响的代码
- `web/` 管理页。

### 用户影响
- 管理员可用浏览器演示灰度与终判。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新（如何授予 ADMIN：已有内部接口，前端不暴露 grant-admin）

## 时间线评估

小到中：约 0.5–1 天。

## 风险

- **前端根据 role 自己放行** → 缓解：只隐藏菜单，请求仍打网关，403 必须可见。
- **把 grant-admin 做成页面** → 缓解：明确禁止；ADMIN 仍走内部凭证接口，文档说明。
- **无申诉列表导致难演示** → 缓解：业务控制台申诉后记下 appealId；管理页手工输入。
