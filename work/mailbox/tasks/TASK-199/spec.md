# 任务书：探针测试 Nacos 隔离修复（TASK-199 / fix-probe-nacos-isolation）

唯一指令源：本任务书 + spec/changes/fix-probe-nacos-isolation/。冲突以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh。

## §0 红线（八条，违反即 FAILED）

1. **白名单制改动面**：C-01 仅探针测试文件——user/record/verify/leaderboard/mapmatch/gateway 六服务 `src/test/java/**/health/*ProbeTest.java`（gateway 为 `gateway/GatewayProbeTest.java`），按需最小集（至多 6 文件，不必要触碰登记依据）；C-02 恰 4 台账文件（§4）。零触碰：一切 src/main、application 配置、pom、ci.yml、docker-compose*、scripts/verify/*、web、sql、其余测试文件。
2. **断言零改动**：既有断言语义（liveness UP / readiness 组结构 / 禁 db-redis-UP）、accommodation 结构（@Import / ProbeApp / @Primary mock / @ConditionalOnMissingBean 让位）零改动；只补 Nacos 隔离属性或等价排除。
3. **CI 等效验证义务**：以属性覆盖（不可达地址/禁用开关）模拟无 Nacos/Redis/MySQL/RocketMQ 环境逐服务验证探针测试全绿；禁止停共享中间件容器（环境态留予后续课题）；无法构建等效环境 → 停止条件 2。
4. **offline 逐位不减**：609（36/48/130/151/159/70/15 以实测为准）逐位不减不增（本课题纯属性修复不加用例）；既有用例零回归。
5. **static 触源门**：record/leaderboard 测试源在 checkstyle 扫描范围，改动后 `--static=record-service` 与 `--static=leaderboard-service` 均 rc=0。
6. **token 29 项只增不减**：tracked `git grep -cF` 权威口径；台账纯追加；handoff 增设「只改清单」小节（N-3 采纳，使契约门 --open 态走真实判据 B 比对）。
7. **提交纪律**：两笔 `type(scope): description (TASK-199)` 未推送不建 PR；新文件/行尾规范照旧；MSYS sed -i 禁用于 tracked 文件；CI 红禁重试刷绿。
8. **零环境泄漏读数**：handoff 须登记 CI 等效验证方法（属性覆盖口径）与六探针逐个读数；不以「本机中间件在跑」作为任何 PASS 依据（TASK-198 假绿教训）。

## §1 归因基线（第 39 次 CI run 38113472212）

- user 档 UserProbeTest 3 例 `Failed to load ApplicationContext`，首因 Nacos gRPC 127.0.0.1:9848 Connection refused；failure threshold 跳过后掩盖首栈。
- record/verify/leaderboard/mapmatch 四档被 SKIPPED，其探针测试在 CI 从未跑到（同病未证）；gateway 探针 CI 实跑绿；record ProbeApp 定制上下文（扫描排除）可能已隔离。
- 本地 offline 全绿为本机 Nacos 在跑的假绿——环境依赖泄漏定性，非 flaky。

## §2 预注册设计

- UserProbeTest 现有属性段含 `spring.cloud.nacos.discovery.fail-fast=false`——就地扩展补 `spring.cloud.nacos.discovery.enabled=false` + `spring.cloud.nacos.config.enabled=false`（其余测试同范式；record 若已排除则登记依据不动；gateway 登记依据不动）。
- 等效验证口径建议：测试 JVM 属性覆盖 nacos server-addr 指向不可达地址（如 127.0.0.1:1）或直接以禁用开关跑——执行侧可择稳健者，登记方法与读数。

## §3 停止条件

1. 补属性后探针测试在等效口径仍红且归因超出 Nacos 范畴（如暴露新的环境依赖）；
2. 无法构建 CI 等效验证环境（须申报，不得以本机中间件替代）；
3. 需触碰白名单外文件（含任何 src/main、配置、pom）；
4. offline 逐位减少或既有用例回归；
5. 发现 D3 缺陷修复必须先行（探针测试修复依赖生产代码改动才能绿）。

## §4 白名单（只改清单）

- **C-01 实施笔**（至多 6 文件）：`user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`、`record-service/.../health/RecordProbeTest.java`、`verify-service/.../health/VerifyProbeTest.java`、`leaderboard-service/.../health/LeaderboardProbeTest.java`、`mapmatch-service/.../health/MapMatchProbeTest.java`、`gateway-service/.../GatewayProbeTest.java`（按需最小集，不触碰者登记依据）
- **C-02 台账笔**（恰 4 文件）：spec/changes/fix-probe-nacos-isolation/tasks.json、work/mailbox/tasks/TASK-199/spec.md（§7 回填）、work/mailbox/tasks/TASK-199/handoff.md（新建，含「只改清单」小节）、work/mailbox/PLAN.md（纯追加）
- 提交主题：C-01 `test(obs): 探针测试Nacos隔离属性修复CI环境依赖泄漏（TASK-199）`；C-02 `docs(mailbox): 登记TASK-199探针隔离修复验收与台账闭环（TASK-199）`

## §5 受保护 token（29 项）

开工实测 SUM（预期 2030）→ 收口复测只增不减；清单见 TASK-187 handoff §7。

## §6 门禁结构

- **开工规程**：HEAD=派发笔、工作区净、CI 红 run 38113472212 归因复核（日志签名与本任务书 §1 一致）
- **收口门禁**：offline 609 逐位 + static 双模块 + 契约门双态（--open TASK-199 --baseline=<派发笔哈希>，判据 B 走真实比对）+ token 复测 + 只改清单全等 + 零越界 + 台账纯追加
- **外部终验**：push 后第 40 次 CI 全绿（重点：user 档探针全绿 + 四档探针 CI 首跑），红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`（回填显式哈希）`
- C-01 实施笔：`（回填显式哈希）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff 给出）`

### §7.2 实施读数（回填：逐测试改动口径与不动依据 / CI 等效验证方法与六探针读数）

### §7.3 门禁读数（回填：offline 逐位 / static 双模块 / 契约门双态 / token / 只改清单 / diff --check / 行尾）
