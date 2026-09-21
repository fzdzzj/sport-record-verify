# TASK-104 认证增强与工程化（F11/F12/F20/F21，F14 关闭说明）

## 目标
修复 4 条发现：登录锁定按手机号可被滥用 DoS（F11）、无登出吊销（F12）、服务未容器化（F20）、CI 缺口（F21）；并书面关闭 F14。明细先读 work/mailbox/findings-summary.md 对应条目。

## 范围外
- 不改 application.yml/properties、不改 user-service 的 JwtUtil.java / FriendService.java（其他任务占用）
- 不改 docker-compose.yml（TASK-101 占用）；F20 交付独立新文件 docker-compose.services.yml
- 不引入运行态新依赖（离线仓库 .m2-repo，settings 为 .mvn-settings.xml）；构建插件若离线仓库没有对应构件，禁止新增，写 handoff「待主 agent 决定」

## 先读文件
- work/mailbox/findings-summary.md（F11/F12/F14/F20/F21）
- user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
- user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
- user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
- api/src/main/java/com/sportverify/api/auth/AuthApi.java（对照契约，不改）
- .github/workflows/ci.yml、pom.xml（根）、各服务模块 pom.xml（确认哪些是 boot 可执行模块）

## 只改文件
- user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
- user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
- user-service/src/test/java/com/sportverify/user/auth/**（AuthServiceTest 等）
- 各服务模块根新增 Dockerfile（gateway-service/user-service/record-service/verify-service/leaderboard-service/mapmatch-service）
- docker-compose.services.yml（新文件）
- .github/workflows/ci.yml
- pom.xml（根，仅 F21 需要且插件离线可用时）

## 要做的修改
1. F11 锁定叠加来源维度：登录失败计数与锁定键从 `auth:{fail,lock}:{phone}` 改为 `{phone}:{clientIp}`；AuthController 从请求取 IP（优先 X-Forwarded-For 首段，兜底 remoteAddr）传入 service。连续失败仍锁，但只锁「该 IP 对该账号」，机主从其他网络可正常登录。方法签名变化只允许加参数（重载保留旧签名 @Deprecated 亦可）。
2. F12 登出：AuthController 加 `POST /api/auth/logout`，入参 refreshToken；AuthService.logout：解析（失败也按成功返回，防探测）→ 删除 `auth:refresh:{userId}:{jti}` 存活键。access token 15min 窗口不吊销——在 AuthController javadoc 写明该决策与理由（窗口短、黑名单成本高）。
3. F14 关闭：在 handoff 写明关闭理由（规范要求规则链「收集全部命中」，短路会改变证据完整性语义），不改代码。
4. F20 容器化：为 6 个可执行服务各写多阶段 Dockerfile（maven:3.9-eclipse-temurin-21 构建 → eclipse-temurin:21-jre 运行；构建阶段用 `-s .mvn-settings.xml` 挂载本地仓库缓存由构建者自行决定，Dockerfile 内用默认 settings 即可）；新增 docker-compose.services.yml 以独立文件编排 6 个服务（build 指向各 Dockerfile、depends_on 中间件、env 引用 .env），不改动既有 docker-compose.yml。
5. F21 CI：ci.yml 增补——（a）`docker compose config -q` 配置校验步骤；（b）测试报告上传 artifact。静态检查/依赖扫描插件若离线仓库无构件（spotless=com.diffplug、owasp 均无），不加入，在 handoff 写明「待联网环境评估」。

## 验收命令
1. `mvn -s .mvn-settings.xml -q -pl user-service -am test` 全绿
2. `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` 通过（无 docker 时跳过并 handoff 说明）
3. 6 个 Dockerfile 均存在且为多阶段（grep -c "^FROM" 每个 ≥2）
4. `grep -c "logout" user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java` ≥ 1

## 完成定义
- 各条落地或书面关闭，验收命令全过
- 写 work/mailbox/tasks/TASK-104/handoff.md：完成情况 / 每条发现改动点 / F14 与 F21 未实施部分的说明 / 「待主 agent 决定」清单
- 回报 ≤300 字：产出 / 校验结果 / 未解决项 / 待决策项

## 约束
- 你是上述文件的唯一写入者；最多 1 次修复重试
- 不准猜测，缺信息写 handoff「待主 agent 决定」
