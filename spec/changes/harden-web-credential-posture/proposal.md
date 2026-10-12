# 提案：harden-web-credential-posture（TASK-205 web 凭证面与 CORS 生产姿态）

## 1. 背景与缺口（2026-10-12 指导侧侦察实证）

- web/src/utils/token.ts L5-L33：access/refresh/role 三键全量持久化 localStorage；refresh 存活 7 天，
  XSS 任意脚本可读 → 账号接管窗口最长 7 天。文件注释自认「生产可考虑 httpOnly cookie」。
- web/src/api/client.ts L131-L149：401 单次 refresh 重试，refresh 从 localStorage 读取后经请求体
  传 POST /api/auth/refresh；web/src/api/notificationWs.ts L24-L62：STOMP CONNECT 帧级鉴权使用
  Authorization Bearer 头（access 对 JS 可读是硬约束，全量 cookie 化不可行）。
- user-service AuthController：login/refresh 以 JSON body 下发 refreshToken，logout 从 body 收值；
  AuthService / TokenDTO / InternalAuthController 与下发通道零耦合，controller 层即可完成 cookie 包装。
- 全仓实证 /internal/auth 无 Feign 调用方：内部面为治理面工具端点（共享密钥保护），可保持 body 通道零改动。
- gateway-service application.yml L77-L95：globalcors allowedOrigins 硬编码两个本地开发地址
  （127.0.0.1:5173 / localhost:5173），allowCredentials=true，生产域名无注入入口——TASK-191
  生产姿态治理的漏网项（密钥/鉴权已 strict 化，CORS 域名仍为开发态硬编码）。
- 部署形态实证：开发态 vite proxy 同源转发（5173 → 8080，CORS 实际不触发）；compose 无 web 容器，
  生产域名未定 → SameSite=Lax 同域反代为合理默认形态。

## 2. 方案（完整档，R1+R2+R3）

### R1 refresh 下发通道 cookie 化（仅对外面 /api/auth）
- login 成功：响应追加 Set-Cookie（refresh_token=<值>；HttpOnly；SameSite=Lax；Path=/api/auth；
  maxAge 与 refresh 存活 TTL 对齐），随后将 TokenDTO.refreshToken 置 null 再返回（body 不泄漏）。
- refresh：读值 cookie 优先、请求体兜底（平滑兼容），以取到的值走既有 AuthService.refresh 轮换；
  成功后同样新 Set-Cookie + body 置 null。
- logout：读值 cookie 优先、请求体兜底 → 既有 AuthService.logout 吊销；响应 Set-Cookie maxAge=0 清除。
- 半径控制：AuthService、TokenDTO（api 模块）、InternalAuthController 全部零改动——纯 controller 层包装。

### R2 前端适配（3 文件）
- token.ts：删除 refresh 持久化（不再 setItem）；setTokens 签名收窄为 (accessToken, role)；
  clearTokens 保留对旧 refresh 键的一次性 removeItem（清理存量残留）。
- client.ts：401 refresh 调用改为无 body 凭证 + withCredentials: true（同源无害、跨域必需）；
  删除「无本地 refresh 才失败」的前置判断（cookie 可见性 JS 不可知，统一由服务端 401 裁决）；
  _retry 防循环保留。
- login.page.vue L63-L64：登录成功判空不再依赖 refreshToken；setTokens 适配新签名。
- main.ts 路由守卫（hasAccessToken）与 authFailHandler 机制零改动。

### R3 网关 CORS 生产姿态
- allowedOrigins 改环境变量占位（CORS_ALLOWED_ORIGINS），默认回退当前两个本地开发地址——
  本地零扰动、生产注入，对齐 TASK-191「环境变量注入 + 演示默认回退」范式。

### 效果
XSS 无法读取 refresh（HttpOnly），凭证窃取窗口由 refresh 存活 7 天收敛为 access 15 分钟；
CORS 域名获得生产注入入口。

## 3. 备选方案与否决理由

- 全量 token cookie 化：被 STOMP CONNECT Bearer 鉴权阻塞（access 必须 JS 可读），且引入 CSRF 面，否决。
- 轻量档（refresh 改 sessionStorage）：半径 1-2 文件但 XSS 运行时仍可读、跨标签页仍可见，增益有限，否决。
- access jti 黑名单 / refresh TTL 缩短：TASK-193 已裁定不重开黑名单；TTL 缩短伤用户体验且不解决存储面，否决。

## 4. 后置议题（登记不实施）
- 生产部署形态确定后（域名/nginx 反代）：CORS 生产域名注入实操 + 跨域 cookie 场景（SameSite=None+Secure）评估。
- Secure cookie 标志与 TLS 终止位置（反代层）的协同加固。
- web 前端测试基建（当前仅 build 脚本，无单测框架）。
