# 提案：prove-authz-cookie-e2e（TASK-206 真实跨进程链路验证）

## 1. 背景
- TASK-204 非阻断 N3:X-User-Id→403 归属校验四端点仅纯 JVM 服务层单测,真实链路(网关注入头→服务控制器→403)未跑。
- TASK-205 非阻断 N1:refresh cookie 通道(HttpOnly/SameSite=Lax/Path=/api/auth)仅纯 JVM 控制器层断言,真实进程 Set-Cookie/带 cookie 轮换/重放 401 链路未跑。
- TASK-200 先例:本机 java -jar 生产实起四服务验证 liveness 可行,中间件共享容器在跑禁停。

## 2. 方案（纯验证，零生产代码改动）
- V1 归属校验链路:起 gateway+user+record 三 jar(连已跑中间件容器),真实登录用户 A/B,A 携 token 访问 B 的
  GET /api/records/{id}/points 断言 403,B 访问断言 200(视环境稳度可加 accept/reject 同构断言)。
- V2 cookie 链路:login 断言真实 Set-Cookie 五属性+body null;带 cookie refresh 断言 200+新 Set-Cookie+body null;
  旧 cookie 重放断言 401;logout 断言 200+maxAge=0+后续 refresh 401。
- 分级:MEDIUM(2026-10-12 三档制)——零核心逻辑/安全语义改动;任一断言失败=潜在缺陷实证,停手回报,禁现场修码。

## 3. 半径与产出
- 生产代码/compose/CI/中间件容器零改动;服务 jar 用已构建产物或本机 mvn -pl 打包(scratch 库)。
- 中间产物(服务日志、curl 脚本、cookie jar)置 .trae/tmp 用后删;docker-compose.override 禁用(历史教训)。
- 交付:验证报告(逐断言+实测输出摘录)登记台账,两 N 项闭环。
