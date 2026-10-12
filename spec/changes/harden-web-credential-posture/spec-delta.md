# spec-delta：harden-web-credential-posture（TASK-205）

## MODIFIED 需求

### M1 对外面登录凭证下发（原：JSON body 携 accessToken/refreshToken/role）
登录成功响应改为双通道：access/role/tokenType/expiresIn 经 JSON body 下发；
refreshToken 仅经 Set-Cookie（HttpOnly；SameSite=Lax；Path=/api/auth；maxAge 与 refresh 存活 TTL 对齐）
下发，body 中该字段置 null。内部面 /internal/auth 保持 body 通道不变（治理面工具端点，共享密钥保护）。

### M2 对外面刷新凭证读取（原：仅请求体）
POST /api/auth/refresh 读值 cookie 优先、请求体兜底；以取到的值走既有轮换语义
（GETDEL 原子消费、封禁/删号 401、重放 401——TASK-202 语义零改动）；成功后新 Set-Cookie + body 置 null。

### M3 对外面登出凭证读取与清除（原：仅请求体）
POST /api/auth/logout 读值 cookie 优先、请求体兜底 → 既有吊销语义（幂等，不泄漏有效性）；
响应追加 Set-Cookie maxAge=0 清除浏览器侧残留。

### M4 前端凭证存储（原：三键 localStorage 持久化）
access/role 保持 localStorage（短窗/低敏）；refresh 不再持久化（HttpOnly cookie 托管）；
前端刷新调用无 body 凭证 + withCredentials；存量 refresh 键在 clearTokens 时一次性清理。

### M5 网关 CORS 源清单（原：硬编码两个本地开发地址）
allowedOrigins 改环境变量注入（逗号分隔），默认回退本地开发地址；
allowCredentials=true 与精确 origin 清单的既有约束保持不变。

## ADDED 需求

### A1 AuthController 层 cookie 语义回归测试
新建 AuthControllerTest（纯 JVM）：Set-Cookie 属性断言（名称/HttpOnly/SameSite/Path）；
body 不泄漏 refreshToken；cookie 优先于请求体；logout 清除 cookie；轮换/吊销委托 AuthService 语义零改动。

## 约束
- AuthService、api 模块 DTO（TokenDTO/RefreshRequestDTO/LogoutRequestDTO 等）、InternalAuthController 零改动。
- offline 基线只增不减：user 模块净增（AuthControllerTest 新增），其余六模块逐位不变。
- web 验证以 vite build（CI web job）为权威；前端不引入测试框架（后置议题）。
