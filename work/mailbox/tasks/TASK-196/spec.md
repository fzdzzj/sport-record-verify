# 任务书：存量 CRITICAL 依赖升级治理专项（TASK-196 / harden-dep-critical-pin）

唯一指令源：本任务书 + spec/changes/harden-dep-critical-pin/（proposal / tasks.json / spec-delta）。冲突以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh（禁手跑 mvn 构建测试；`mvn dependency:tree` 只读分析允许）；退出码 0/1/3/2，3=未定不得记 PASS。

## §0 红线（十条，违反即 FAILED）

1. **白名单制改动面**：C-01 恰 7 文件——父 pom.xml、gateway/user/record/verify/leaderboard 五模块 pom（fastjson exclusions）、`.trivyignore`（收缩）；C-02 恰 5 台账文件（§4）。零触碰：业务源码与测试源码、ci.yml、docker-compose*、scripts/verify/*、web、sql、common 与 mapmatch 模块 pom（侦察实证不含目标链，若实施中实证需触碰 → 停止条件 6）、ADR 文档。
2. **pom-only**：零业务代码改动；任一阶段发现必须改 Java 源码才能适配 → 停止条件 3。
3. **三 BOM 零改动**：spring-boot-dependencies:3.2.4、spring-cloud-dependencies:2023.0.1、spring-cloud-alibaba-dependencies:2023.0.1.0 版本号不动（ADR-0001 基线锁定）；仅 dependencyManagement 精准 Pin 补丁构件。
4. **Pin 目标版本**（预注册，以 Trivy 实测检出为最终判据）：tomcat-embed-core 10.1.58、io.netty:netty-bom 4.1.137.Final（BOM 整体锁定）、spring-framework-bom 6.1.29、bcprov-jdk18on 1.85、（兜底时）fastjson2:fastjson-to-fastjson2 2.0.53。Pin 形态（BOM import / 逐构件）按 Maven First-Declaration-Wins 实测并登记。
5. **.trivyignore 收缩纪律**：仅删除「该阶段 Trivy 未豁免实测检出为 0」的 CVE 登记行及其分组注释；文件保留（头部规则注释不动）；ci.yml 零触碰；某项升级后仍检出 → 行保留 + 偏差登记，禁谎报消除。
6. **回归硬门**：offline 569（36/44/127/137/149/64/12）逐位不变（升级禁增删改测试行为）+ static 双模块（--static=record-service 与 --static=leaderboard-service）rc=0。
7. **每阶段门禁流水线**（阶段 1/2/3 各跑一遍留证）：offline 569 → static 双模块 → 6 服务镜像构建全绿 → Trivy 扫 6 镜像验证本阶段目标 CVE 未豁免检出 0 → 收缩 .trivyignore → Trivy 门禁跑次全绿。
8. **零收益百分比 claim**：可登记机制性事实（Pin 版本、消除项计数、.trivyignore 行数变化），禁安全改善百分比表述。
9. **token 29 项只增不减**（开工实测，TASK-195 收口真值 2030 参照）；新文档禁枚举字面量；台账纯追加（tasks.json 勾选翻转除外）。
10. **提交纪律**：两笔提交 `type(scope): description (TASK-196)`，均未推送不建 PR；CI 红禁重试刷绿按签名归因；修改 pom 保持既有行尾，`git diff --check` 干净。

## §1 侦察实证（引用侦察报告，开工不必重跑，异常才复验）

- 父 pom 为 import BOM 结构（非 starter-parent 继承）；覆盖 imported BOM 版本须将目标构件声明于 spring-boot-dependencies import 之前（First-Declaration-Wins）。
- 传递链实证：tomcat（starter-web → starter-tomcat，gateway 无）；netty（gateway 经 sentinel、其余 4 服务经 redisson:3.27.2，mapmatch 无）；bcprov（全 6 服务经 spring-cloud-starter → spring-security-rsa:1.1.2）；webmvc（5 Web 服务）；webflux（仅 gateway）；**fastjson 非 common 引用**（4 服务经 rocketmq-spring-boot-starter:2.3.1 → rocketmq-acl:5.1.4 → fastjson:1.2.83；gateway 经 sentinel-transport-common 1.8.6 引入 1.2.83_noneautotype 变体），全仓业务代码 0 处引用，JSON 统一 Jackson。
- 修复版本：Tomcat 10.1.58+（7 项全）、netty 4.1.137.Final+、Spring 6.1.29+（webmvc/webflux 2 项）、BC 1.85+（2 项）、fastjson 1.x EOL。
- 本仓未启用 RocketMQ ACL（无 plain_acl.yml）。

## §2 预注册设计（三阶段）

- **§2.1 阶段 1（消 10 项）**：父 pom dependencyManagement 顶部 Pin netty-bom 4.1.137.Final + tomcat-embed-core 10.1.58 + spring-framework-bom 6.1.29 → §0-7 流水线 → dependency:tree 核 netty 无 4.1.107 残留、无版本分裂 → .trivyignore 删 Tomcat×7 / Netty×1 / Spring×2 行及分组注释。
- **§2.2 阶段 2（消 2 项）**：父 pom Pin bcprov-jdk18on 1.85 → 流水线（重点认证域 JWT/BCrypt 用例全绿，Trivy BC 2 项检出 0）→ 删 BC×2 行。
- **§2.3 阶段 3（消 1 项）**：四模块 pom 对 rocketmq-spring-boot-starter 加 fastjson exclusions；gateway pom 对 sentinel 链加 exclusions → 流水线（重点 MQ 收发用例全绿）；若类缺失 → 父 pom Pin fastjson-to-fastjson2 2.0.53 桥接兜底（选型与理由登记）→ Trivy fastjson 检出 0 → 删最后 1 行，`.trivyignore` 清至头注释空壳。
- **§2.4 终态**：零豁免 Trivy 门禁跑次 6 镜像全绿；dependency:tree 终态实证（无 fastjson:1.2.83、netty 统一、bcprov 1.85）。

## §3 停止条件（触发即停手回报）

1. offline 用例数漂移或任何失败/报错；
2. static 双模块任一 rc≠0 或新增违规；
3. 任一阶段发现必须修改 Java 源码才能适配（pom-only 破防）；
4. 6 镜像构建失败或 Trivy 出现**新增未登记** CRITICAL/HIGH（升级引入新漏洞）；
5. fastjson 排除后类缺失且桥接包不可解（NoClassDefFound 持续）；
6. 需触碰白名单外文件（含 common/mapmatch pom、ci.yml、ADR）；
7. 某项 Pin 后 Trivy 仍检出该 CVE（修复版本判定失效）→ 行保留、登记偏差、按用户裁决继续或终止。

## §4 白名单（只改清单）

- **C-01 实施笔**（恰 7 文件）：pom.xml（父）、gateway-service/pom.xml、user-service/pom.xml、record-service/pom.xml、verify-service/pom.xml、leaderboard-service/pom.xml、.trivyignore
- **C-02 台账笔**（恰 4 文件）：spec/changes/harden-dep-critical-pin/tasks.json、work/mailbox/tasks/TASK-196/spec.md（§7 回填）、work/mailbox/tasks/TASK-196/handoff.md（新建）、work/mailbox/PLAN.md（纯追加；findings 已清零，本课题无 findings 标注）
- 提交主题：C-01 `build(deps): 精准Pin安全补丁版本并根除fastjson传递存量（TASK-196）`；C-02 `docs(mailbox): 登记TASK-196依赖升级治理验收与台账闭环（TASK-196）`

## §5 受保护 token（29 项）

开工实测 SUM（预期 2030 持平）→ 收口复测只增不减；清单见 TASK-187 handoff §7；字面量禁写入新文档。

## §6 门禁结构

- **开工规程**：基线 HEAD=派发笔、工作区净、token 开工实测、offline 569 基线确认（TASK-195 台账引用，异常才重跑）
- **预提交门禁**：三阶段流水线读数齐（§0-7）；`git diff --check` 干净；行尾核验；词面门（改动文件集 ZERO_HIT，本课题无 ci.yml 改动）
- **收口门禁**：offline 569 + static 双模块（mvn-verify.sh 亲跑留证）；契约门双态（--open TASK-196 --baseline=<派发笔哈希> 与无参）rc=0；token 复测；只改清单全等；零越界；台账纯追加
- **外部终验**：push 后第 37 次 CI 全绿（Trivy 门零豁免状态首跑），红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`f701ecedadc429f8f2f2f6027898a93345572898`（`f701ece`）
- C-01 实施笔：`4ddd6321584294a609ae6906fa74986417daaa64`（`4ddd632`）
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）`

### §7.2 实施读数

- **三阶段流水线读数**：
  - 阶段 1：父 pom dependencyManagement 顶部 Pin `netty-bom:4.1.137.Final` 与 `tomcat-embed-core:10.1.59`（偏差 D1：预注册 10.1.58 在 Central 仓库不存在，采用公开补丁版本 10.1.59；偏差 D3：Spring Framework 6.1.29 属 VMware 商业支持专有，Central 仓库不存在，经用户拍板保留 2 项 CVE 并登记偏差）。消除 Tomcat×7 + Netty×1 共 8 项 CVE，收缩 .trivyignore 8 行。
  - 阶段 2：父 pom Pin `bcprov-jdk18on:1.85`，消除 BC×2 项 CVE，认证域用例（JWT/BCrypt）全绿，收缩 .trivyignore 2 行。
  - 阶段 3：五模块（user/record/verify/leaderboard/gateway）pom 排除 fastjson 传递依赖，全仓用例全绿无类缺失，无需 fastjson-to-fastjson2 桥接，消除 fastjson×1 项 CVE，收缩 .trivyignore 1 行。
- **.trivyignore 收缩前后行数对照**：开工 46 行（登记 13 项 CVE 及分组注释）→ 收缩后 16 行（保留头部规则注释与 2 项 Spring 商业专属 CVE 登记）。
- **dependency:tree 终态实证**：`mvn dependency:tree` 实证全仓 0 处 fastjson:1.2.83；netty 统一锁定 4.1.137.Final 无版本分裂；bcprov 统一锁定 1.85。

### §7.3 门禁读数

- **offline 569 逐位**：`36/44/127/137/149/64/12` = 569，Failures: 0, Errors: 0, Skipped: 0，rc=0。
- **static 双模块**：`--static=record-service` (rc=0) 与 `--static=leaderboard-service` (rc=0) 均通过。
- **6 镜像构建**：gateway/user/record/verify/leaderboard/mapmatch 全绿构建成功。
- **Trivy 收缩验证与门禁跑次**：6 镜像门禁跑次全绿（`--severity CRITICAL --exit-code 1 --ignore-unfixed --ignorefile .trivyignore`），11 项消除项未豁免检出均为 0。
- **契约门双态**：`bash scripts/verify/mailbox-contract.sh --open=TASK-196 --baseline=f701ece` 与无参调用均 rc=0。
- **token 29 项**：开工实测 SUM=2030，收口实测 SUM=2030（只增不减持平）。
- **只改清单**：C-01 恰 7 文件，C-02 恰 4 文件，零越界。
- **diff --check 与行尾**：`git diff --check` rc=0 干净；各 pom 与 .trivyignore 保持既有行尾。
