# spec-delta：prove-authz-cookie-e2e（TASK-206）

## ADDED 需求（验证型，零生产代码改动）

### A1 真实链路验证矩阵（V1 归属校验）
gateway+user+record 真实进程下:用户 A 携有效 access token 访问用户 B 的
GET /api/records/{B记录id}/points → 403;B 本人访问 → 200。断言经网关真实注入 X-User-Id 通道。

### A2 真实链路验证矩阵（V2 cookie 通道）
- login → 响应头 Set-Cookie 含 refresh_token=<值>/HttpOnly/SameSite=Lax/Path=/api/auth,且 body data.refreshToken 为 null;
- 带 cookie refresh → 200 + 新 Set-Cookie + body null;
- 旧 cookie 重放 → 401(轮换语义真实生效);
- 带 cookie logout → 200 + Set-Cookie maxAge=0,此后 refresh → 401。

## 约束
- 零生产代码/配置/CI/compose 改动;共享中间件容器禁停禁改;服务进程与临时文件验证后全量清理,工作区 porcelain 空。
- 任一断言失败=缺陷实证:停手回报,禁现场修码(升级为 FULL 缺陷课题另立)。
- offline 逐位不变 655、token 2030(零改动的直接证据)。
