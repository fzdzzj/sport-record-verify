# TASK-270 立项任务书（BadgeServiceTest 单测隔离度加固）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导侧终裁；开工基线 main @ `a7a3159`；LIGHT 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链（实测暴露与指导侧亲验坐实，2026-10-18）

在 TASK-269 封盘批次推送到远程后，GitHub Actions CI run `39107840592` 在 `Build and test` 阶段失败：
```text
[ERROR] BadgeServiceTest.unwearBadge_resetsAllEquippedFlags:330 » MybatisPlus can not find lambda cache for this entity [com.sportverify.record.entity.UserBadge]
```
**根因分析与本地复现**：
1. `BadgeServiceTest` 之前将 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBadge.class);` 仅写在 `wearBadge_success_resetsAllThenEquipsTarget` 测试方法内部，未置于 `@BeforeEach`；
2. JUnit 5 不保证用例执行顺序。在 CI 独立的 Maven JVM 进程中，当 `unwearBadge_resetsAllEquippedFlags` 先于 `wearBadge` 执行时，MyBatis-Plus 的 `LambdaUpdateWrapper<UserBadge>` 因缺少 `UserBadge` 的实体缓存而抛出异常；
3. 本地执行单用例 `mvn test -pl record-service -Dtest=BadgeServiceTest#unwearBadge_resetsAllEquippedFlags -s .mvn-settings.xml -o` 100% 精确复现该错误。

---

## 2. 只改清单（白名单，恰 1 业务测试 + 1 台账 = 2 文件）

严格限制在以下 2 个文件内，严禁触碰白名单外任何文件：

1. `record-service/src/test/java/com/sportverify/record/service/BadgeServiceTest.java`
2. `work/mailbox/tasks/TASK-270/handoff.md`

---

## 3. 核心设计与实施要求

- **R1 补齐 `@BeforeEach setUp()` 初始化**：
  - 在 `BadgeServiceTest.java` 引入 `import org.junit.jupiter.api.BeforeEach;`；
  - 增设 `@BeforeEach void setUp()` 方法，执行：
    ```java
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBadge.class);
    ```
    （与仓内既有 `GoalServiceTest`、`TrackPointArchiveServiceTest` 等保持完全一致的家法规范）；
  - 移除 `wearBadge_success_resetsAllThenEquipsTarget` 内部单处冗余的初始化调用；
- **R2 隔离度验证**：
  - 单独运行 `BadgeServiceTest#unwearBadge_resetsAllEquippedFlags`，确保其能独立成功运行；
  - 运行 `record-service` 全量测试，确保 351 个测试全部保持通过。

---

## 4. 门禁要求

1. **Surefire 测试全量只增不减**：
   - `record-service` 351 测全绿（0 失败、0 错误、0 跳过）；全仓七模块 1063 测保持；
2. **静态检查 Checkstyle 保持 0 违规**：
   - `mvn-verify.sh --mode=offline --static=record-service` 退出码 0；
3. **契约门**：
   - `bash scripts/verify/mailbox-contract.sh --open=TASK-270 --baseline=a7a3159` 退出码 0；
4. **Token 门（29 项历史指标）**：
   - 29 项指标改动文件内零命中，C-01 保持不变，C-02 自枚举各 +1；
5. **词面门**：
   - 四 locale 全 `ZERO_HIT`（退出码 1）；
6. **差异与格式检查**：
   - `git diff --check` 输出为空，纯 LF，无 BOM。

---

## 5. 实施纪律

- 遵循双提交纪律（C-01 修复单测 + C-02 提交 handoff.md）；
- 封盘前严禁提前向远程推送。
