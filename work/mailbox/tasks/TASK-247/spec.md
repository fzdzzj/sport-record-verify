# TASK-247 任务书（spec.md）

> **课题**：E5 生产部署姿态演练——CORS 动态多域名注入、跨域 Cookie 凭证透传与 TLS 终止协同演练（候选 H2）  
> **级别**：MEDIUM 档  
> **责任服务**：`gateway-service`、`compose`、`scripts`、`docs`  
> **开工基线**：以当前 HEAD（立项准备提交）为准  

---

## 1. 业务目标与价值

1. **消除 TASK-205 长期挂账技术债**：
   - TASK-205 将 `refresh_token` 迁移为 HttpOnly Cookie，并在网关将 CORS 配置参数化为 `${CORS_ALLOWED_ORIGINS:...}`，但当时遗留了「真实跨域/生产姿态未跑」的未覆盖项；
   - 生产环境中网关部署于反向代理（Nginx / K8s Ingress / 云厂商 SLB）之后，涉及真实环境变量动态注入、多域名白名单、跨域 Cookie 凭证与反向代理 TLS 终止协同，必须具备确凿的生产级自动化演练验证。
2. **反向代理 TLS 终止协同（Forwarded Headers）**：
   - 网关配置 `server.forward-headers-strategy: framework`，确保网关在反向代理终止 TLS 时能够通过 `X-Forwarded-Proto`（https）、`X-Forwarded-Host`、`X-Forwarded-Port` 正确识别客户端真实请求协议与域名，防止构造重定向回退 HTTP 或导致 Cookie 属性错乱。
3. **CORS 动态多域名与凭证安全边界**：
   - 验证生产环境通过 `CORS_ALLOWED_ORIGINS` 注入逗号分隔的多域名（如 `https://sports.example.com,https://admin.example.com`）时，网关正确响应 `Access-Control-Allow-Origin` 与 `Access-Control-Allow-Credentials: true`，对非法或恶意 Origin（如 `https://evil.com`）予以拒绝（不返回 CORS 头）。
4. **跨域 Cookie 凭证透传验证**：
   - 验证跨域客户端发起带 `withCredentials` 的认证请求（如 `/api/auth/refresh` 携带 `Cookie: refresh_token=xxx`）时，网关不过滤、不剥离 Cookie 请求头，完整且安全地透传至下游服务。

---

## 2. 严格改动文件白名单

实施侧仅允许在以下白名单内修改或新增文件，禁止超出范围：

1. `gateway-service/src/main/resources/application.yml`
2. `docker-compose.services.yml`
3. `scripts/verify/env.example`
4. `gateway-service/src/test/java/com/sportverify/gateway/GatewayProductionPostureTest.java`
5. `README.md`
6. `work/mailbox/tasks/TASK-247/spec.md`
7. `work/mailbox/tasks/TASK-247/handoff.md`

**零触碰红线声明**：
- `record-service`、`verify-service`、`user-service`、`leaderboard-service` 绝对零触碰；
- 根与各子模块 `pom.xml`、`web/package.json` 绝对零触碰（零新外部依赖）；
- `work/mailbox/PLAN.md` 绝对禁止子 Agent 触碰。

---

## 3. 详细判别式与实现契约

### 3.1 网关生产协同配置
- **`gateway-service/src/main/resources/application.yml`**:
  - 在 `server` 节点下配置：
    ```yaml
    server:
      port: 8080
      forward-headers-strategy: framework
    ```
  - 其余路由规则、Sentinel 配置、globalcors 均保持不变。

### 3.2 Compose 与环境变量示例对齐
- **`docker-compose.services.yml`**:
  - 在 `gateway-service` 的 `environment` 节补充：
    ```yaml
    CORS_ALLOWED_ORIGINS: "${CORS_ALLOWED_ORIGINS:-http://127.0.0.1:5173,http://localhost:5173}"
    ```
- **`scripts/verify/env.example`**:
  - 补充 `CORS_ALLOWED_ORIGINS` 示例与生产部署配置注释。

### 3.3 生产部署姿态自动化演练测试
- **`gateway-service/src/test/java/com/sportverify/gateway/GatewayProductionPostureTest.java`**:
  - 新增测试套件，通过 Spring WebFlux / 网关过滤链断言：
    1. **R1 CORS 生产多域名动态注入**：
       - 模拟配置注入 `CORS_ALLOWED_ORIGINS=https://sports.example.com,https://admin.example.com`；
       - 当请求 Origin 为 `https://sports.example.com` 或 `https://admin.example.com` 时，预检 OPTIONS 或 GET 响应包含对应 `Access-Control-Allow-Origin`，且 `Access-Control-Allow-Credentials: true`；
       - 当请求 Origin 为未授权域名（如 `https://attacker.com`）时，不返回 `Access-Control-Allow-Origin`；
    2. **R2 反向代理 TLS 终止与协议感知**：
       - 发起携带 `X-Forwarded-Proto: https`、`X-Forwarded-Host: sports.example.com`、`X-Forwarded-Port: 443` 的请求；
       - 验证请求经适配后协议 scheme 认定为 `https`，符合生产 TLS 终止姿态；
    3. **R3 跨域 Cookie 凭证透传断言**：
       - 发起包含 `Cookie: refresh_token=sample_refresh_token_value` 的请求，验证网关路由与过滤器未剥离该 Cookie 头。

### 3.4 部署文档对齐
- **`README.md`**:
  - 在生产部署或反向代理章节，补充反向代理（Nginx 等）配置建议与 `X-Forwarded-Proto` 协同说明。

---

## 4. 本地门禁要求

1. **后端单元测试**：
   - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl gateway-service -am` 全绿通过。
2. **Compose 校验**：
   - `docker compose -f docker-compose.yml -f docker-compose.services.yml config`（或利用已有的 compose 语法解析检查）无语法错误。
3. **前端类型与单测**：
   - `cd web && pnpm run type-check && pnpm test` 全绿通过（保证无回归）。
4. **台账契约门禁**：
   - 使用 Git Bash 执行 `bash scripts/verify/mailbox-contract.sh --open=TASK-247 --baseline=<开工基线>`，退出码必须为 0。
