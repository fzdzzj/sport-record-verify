# 提案：网关浏览器跨域（可选直连，不替代开发代理）

## Why

Vite 开发代理足以让 `localhost:5173` 访问 `8080`。但若用 `pnpm build` 后的静态页、或浏览器直连网关，会触发 CORS 预检。本仓库网关目前没有 CORS 配置。

这是后端/网关方向，必须与前端页面实现分开，避免「做页面时顺手改网关」把跨域策略做宽。

**背景**：
- Spring Cloud Gateway 需显式 `globalcors` 或等价过滤器。
- 鉴权使用 Authorization 头，预检必须允许该头与 OPTIONS。
- 本地演示源是 `http://127.0.0.1:5173` / `http://localhost:5173`。

**当前状态**：无 CORS；开发靠 Vite proxy。

**期望状态**：网关允许配置的来源列表（默认仅本地 Vite 端口）；允许 Authorization 与常见 GET/POST/PUT/PATCH/DELETE/OPTIONS；不允许 `*` 加凭证。开发代理路径保持不变。不把 CORS 当成鉴权。

## What Changes

- `gateway-service` 增加可配置 CORS（来源列表、允许头、方法）。
- 默认来源仅本地前端开发端口；可用环境变量追加。
- README 说明：`pnpm dev` 走代理不必依赖 CORS；静态资源直连网关才需要。
- 单测或配置测试：预检允许本地来源，不允许随意 `*`。

**明确不做**：改鉴权默认；前端页面；把 CORS 允许源设为 `*`。

## Impact

### 受影响的规范
- ADDED「网关浏览器跨域」。

### 受影响的代码
- gateway-service 配置与测试；README。

### 用户影响
- 浏览器直连网关时不再被浏览器拦截；安全性仍靠 JWT。

### API 变更
- 无业务路径变更；增加 OPTIONS 预检处理。

### 需要迁移
- [x] 文档更新

## 时间线评估

小：约 0.3–0.5 天。

## 风险

- **allowed-origin=*** → 缓解：规范禁止带凭证时使用通配。
- **与鉴权白名单混淆** → 缓解：CORS 只回答浏览器能否读响应，不代替 Bearer。
