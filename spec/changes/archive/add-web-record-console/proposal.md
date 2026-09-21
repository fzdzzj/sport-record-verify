# 提案：Web 业务控制台（提交/判定/申诉/点赞/好友/榜单）

## Why

登录之后仍是空壳，无法演示本项目主链路：提交轨迹 → 校验判定 → 点赞/好友/榜单。本变更只覆盖普通用户业务面，不含规则灰度与终判。

**背景**：
- 提交：`POST /record/api/records`，body 含 requestId、sportType、points[]；鉴权开启后 userId 由网关覆盖。
- 判定：`GET /record/api/records/{id}/verify-result`，verdict 0/1/2。
- 申诉：`POST /record/api/records/{id}/appeal`，仅 REJECTED。
- 点赞：`POST/DELETE/GET /record/api/records/{id}/like`。
- 好友：`/user/api/friends` 申请/同意/拒绝/列表。
- 榜单：`GET /leaderboard/api/leaderboard?type=overall|friend`。
- 仓库已有轨迹样例 JSON（路网匹配脚本 data），前端应内置或读取样例，避免手工填 300 个点。

**当前状态**：无业务页。

**期望状态**：登录用户可粘贴/加载样例轨迹提交，轮询判定结果，对通过记录点赞，管理好友，查看总榜与好友榜。不新增后端列表接口；提交页保存本次会话产生的 recordId 列表即可（诚实：后端暂无「我的记录」列表 API，本变更不顺手造后端列表）。

## What Changes

- 页面：提交记录、判定详情、好友、榜单、点赞入口（可挂在判定详情）。
- 内置至少一套合法样例轨迹（可改编自现有 mapmatch 样例点，注意坐标系字段名对齐 TrackPointDTO：seq/lat/lng/ts）。
- 提交后轮询 verify-result，直到终态或超时提示继续等待（MQ 异步）。
- 错误展示后端 message（3004 幂等、6001 未通过不可赞、5001 好友冲突等）。
- 不新增 Java API。

**明确不做**：管理端规则/终判；地图可视化/ECharts；后端「我的记录」列表接口；mapmatch 独立调试页（可后续另开）。

## Impact

### 受影响的规范
- ADDED「Web 业务控制台」。

### 受影响的代码
- `web/` 业务页面与 API 封装。

### 用户影响
- 可用浏览器走完主演示链路。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新（演示步骤）

## 时间线评估

中：约 1 天。

## 风险

- **没有记录列表 API，刷新丢失 recordId** → 缓解：sessionStorage 保存本机会话提交的 id；文档写明刷新/换浏览器需重新提交或记下 id。不假装有服务端列表。
- **判定尚未完成被当成失败** → 缓解：verdict=0 显示校验中并轮询，不显示「失败」。
- **样例点字段与 DTO 不一致** → 缓解：按 TrackPointDTO 构造，不要直接塞 OSM 原始文件。
