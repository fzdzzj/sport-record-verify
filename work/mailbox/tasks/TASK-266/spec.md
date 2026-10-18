# TASK-266 立项任务书（N3 record-service 循环依赖解耦与启动加固）

> 状态：**已立项待派发**（2026-10-18 指导侧立项；开工基线 main @ 20c3a05；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链（压测实测暴露与指导侧亲验坐实，2026-10-18）

1. **故障现象（TASK-265 压测环境真实暴露）**：
   - 在 TASK-265 真实压测期间，尝试以生产打包 jar 启动 `record-service` 时（`java -jar record-service/target/sport-verify-record-service-0.1.0-SNAPSHOT.jar`），Spring Boot 报 `APPLICATION FAILED TO START` 阻断退出；
   - 报错原文（证据留档于 `.trae/tmp/task265-record-circular-defect.log`）：
     ```
     UnsatisfiedDependencyException: Error creating bean with name 'internalRecordController':
     Unsatisfied dependency expressed through constructor parameter 0:
     Error creating bean with name 'sportRecordService':
     Unsatisfied dependency expressed through method 'setRecordLikeService' parameter 0:
     Error creating bean with name 'recordLikeService':
     Unsatisfied dependency expressed through method 'setSportRecordService' parameter 0:
     Error creating bean with name 'sportRecordService':
     Requested bean is currently in creation: Is there an unresolvable circular reference?
     ```
2. **根因定位与历史遗留分析**：
   - 依赖成环：
     - `SportRecordService.java`（TASK-223 引入）：声明 `private RecordLikeService recordLikeService;` 并通过 `@Autowired(required = false) setRecordLikeService(...)` 注入，用于在删除运动记录提交后 best-effort 清理点赞缓存 `cleanupCache(recordId)`；
     - `RecordLikeService.java`（TASK-221 引入）：声明 `private SportRecordService sportRecordService;` 并通过 `@Autowired(required = false) setSportRecordService(...)` 注入，用于点赞操作前复用 `sportRecordService.assertCanView(...)` 进行读取面权限校验；
     - 双向 setter 注入在 Spring Boot 3.2 下触发严格模式检查（Spring Boot 2.6 起默认 `allow-circular-references=false`）。
   - 历史单测遗漏根因：
     - `SportRecordServiceTest` 与 `RecordLikeServiceTest` 全部采用 Mockito 纯单元测试，未启动 Spring 上下文；
     - 健康探针测试 `RecordProbeTest` 配置了 `spring.main.lazy-initialization=true` 延迟初始化业务 bean，掩盖了急切实例化期的循环引用；
     - 此前压测均在本地 IDE 调试或特定脚本下启动，未常态化生产 jar 冒烟。
3. **修法方案**：
   - **R1（依赖解耦）**：在注入点引入 `@Lazy` 注解（如 `setRecordLikeService(@Lazy RecordLikeService recordLikeService)` 与 `setSportRecordService(@Lazy ...)`），Spring 在急切初始化期自动注入懒加载代理，彻底打破循环依赖；
   - **R2（防倒退配置）**：在 `record-service/src/main/resources/application.properties` 显式声明 `spring.main.allow-circular-references=false`，严防任何新循环依赖引入；
   - **R3（契约单测）**：新增纯 JVM 单测/上下文测试 `RecordServiceCircularDependencyTest.java`，在严格模式（`allow-circular-references=false`）下验证 `SportRecordService` 与 `RecordLikeService` 正常实例化与相互协作，确保 surefire record-service 测试计数由 321 净增至 **322**（只增不减）。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 业务/配置/测试（4 文件）**：
  1. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R1 @Lazy 注入解耦）
  2. `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`（R1 对称注解或规范注释）
  3. `record-service/src/main/resources/application.properties`（R2 显式声明禁止循环引用）
  4. `record-service/src/test/java/com/sportverify/record/service/RecordServiceCircularDependencyTest.java`（R3 容器依赖注入与无循环测试）
- **C-02 台账（1 文件）**：
  5. `work/mailbox/tasks/TASK-266/handoff.md`（实施回填）

零触碰面声明：除上述 4+1 文件外，全仓其他微服务、pom.xml、package.json、前端 web/、docker-compose、.env、sql/ 等 100% 零触碰。

## 3. 修法要求

- **R1（注入点 @Lazy 解耦）**：
  - 在 `SportRecordService.setRecordLikeService` 参数添加 `@org.springframework.context.annotation.Lazy`；
  - 在 `RecordLikeService.setSportRecordService` 参数添加 `@org.springframework.context.annotation.Lazy`；
  - 保持业务调用逻辑与方法签名不变，原有方法功能与 Mockito 单测完全保持兼容。
- **R2（防倒退配置）**：
  - 在 `record-service/src/main/resources/application.properties` 明确添加 `spring.main.allow-circular-references=false`。
- **R3（自动化契约单测）**：
  - 新增 `RecordServiceCircularDependencyTest.java`，构造 AnnotationConfigApplicationContext 或轻量 TestContext，开启 `allow-circular-references=false`，扫描/注册两 Service 与必要 mock/依赖，断言容器成功刷新、两 bean 均成功注入且无异常抛出；
  - record-service surefire 测试计数 321 -> **322**（只增不减）。

## 4. 门禁与验证要求（MEDIUM 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`，全量七模块 surefire 基线 **63/78/221/321/203/110/36** 只增不减（record-service 321→322，净增 1 测，总计 1033 测，零失败零跳过）；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-266 --baseline=20c3a05`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2191 + 29 = 2220**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 修改文件保持既有行尾（LF/CRLF），新测试文件纯 LF 结尾；
- 双提交 C-01（业务/配置/测试）/ C-02（台账）；`git status --porcelain` 收口为空；
- MEDIUM 档由指导侧亲核三块（Git 摘要、高风险定向抽查、门禁与读数裁决）把关，免独立复核子 agent。

## 5. 受限声明（预登记）

1. 本课题聚焦消除 `SportRecordService` 与 `RecordLikeService` 之间的启动期循环依赖环并增加常态化保护，不变更业务权限矩阵（`assertCanView`）与缓存清理（`cleanupCache`）的具体逻辑；
2. 保持依赖边界纯净，不引入任何新的外部库。

## 6. 停止条件与升级路径

- 若解耦修改导致任何既有单测断言失败，立即停手排查；
- 若 surefire 离线门禁任何模块测试失败或计数减少，立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- 七门禁全绿，surefire record-service 净增 +1（321→322，总测试 1033）；
- 新增依赖注入测试在严格无循环引用模式下稳定通过；
- 指导亲核三块全 PASS。
