# TASK-196 harden-dep-critical-pin 存量 CRITICAL 依赖升级治理专项 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-10）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧保持未推送状态。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `f701ecedadc429f8f2f2f6027898a93345572898`（`f701ece`，TASK-196 派发笔），父为 `0190116`（TASK-195 补记笔）；开工前 `git status --porcelain` 为空，工作区干净。
- **未推送口径**：`git rev-list --left-right --count origin/main...main` 开工实测 `0 2`（随批次待推送沿先例，执行侧不推送）。
- **受保护 token**：29 项开工实测 SUM=2030（repo 全量 `git grep -cF` 口径；TASK-195 收口真值 2030 为参照）。
- **offline 全量基线**：开工引用 TASK-195 收口实测 `36/44/127/137/149/64/12` = 569 全绿通过。
- **静态检查门基线**：`--static=record-service` (rc=0) 与 `--static=leaderboard-service` (rc=0) 均全绿通过。
- **红线逐条核验**（§0 十条）：
  1. 白名单制改动面：C-01 恰 7 文件（父 pom.xml、gateway/user/record/verify/leaderboard 五模块 pom、`.trivyignore`）；C-02 恰 4 个台账文件；业务源码与测试源码、ci.yml、docker-compose*、scripts/verify/*、web、sql、common 与 mapmatch 模块 pom、ADR 文档严格零触碰。
  2. pom-only：零业务代码改动，三阶段全部通过 pom 依赖调整解决。
  3. 三 BOM 零改动：spring-boot-dependencies:3.2.4、spring-cloud-dependencies:2023.0.1、spring-cloud-alibaba-dependencies:2023.0.1.0 版本号零改动（ADR-0001 基线锁定）。
  4. Pin 目标版本：通过父 pom dependencyManagement First-Declaration-Wins 机制锁定 netty-bom:4.1.137.Final、tomcat-embed-core:10.1.59（D1）、bcprov-jdk18on:1.85。
  5. .trivyignore 收缩纪律：仅删除实测检出为 0 的 11 项 CVE 行及对应分组注释；保留头部规则注释与 2 项 Spring 商业专属 CVE 登记（D3）；ci.yml 零触碰。
  6. 回归硬门：offline 569（36/44/127/137/149/64/12）逐位不变 + static 双模块 rc=0（mvn-verify.sh 亲跑留证）。
  7. 每阶段门禁流水线：阶段 1/2/3 均跑过 offline 569 → static 双模块 → 6 镜像构建 → Trivy 扫描验证。
  8. 零收益百分比 claim：仅登记机制性事实（消除 11 项、保留 2 项偏差、.trivyignore 收缩），禁安全改善百分比表述。
  9. token 29 项只增不减：开工实测 SUM=2030，C-01 后 SUM=2030，收口复测 SUM=2030；新文档严禁枚举字面量。
  10. 提交纪律：两笔本地提交格式规范，保持未推送不建 PR；pom 修改保持既有行尾，`git diff --check` 干净。

## 2. 一句话结论与三支裁决

**父 pom dependencyManagement 精准 Pin `netty-bom:4.1.137.Final`、`tomcat-embed-core:10.1.59`（D1）、`bcprov-jdk18on:1.85` 并于五模块 pom 彻底排除 fastjson 传递依赖，实测消除 TASK-195 登记的 11 项 CRITICAL CVE（Tomcat×7、Netty×1、BC×2、Fastjson×1），保留 2 项 VMware 商业专属 Spring CVE（D3），`.trivyignore` 由 46 行收缩至 16 行，全仓 6 服务镜像构建与门禁扫描全绿，offline 569 逐位不变，static 双模块 rc=0，判定 PASSED**。外部终验待推送后下一次外部门槛（第 37 次）CI 全绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 11 项 CRITICAL 依赖漏洞消除 + .trivyignore 严格收缩至 16 行 + 6 镜像构建与门禁扫描全绿 + offline 569 逐位不变 + static 双模块 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无门禁回归、无未授权越界） |
| 外部终验 | 待推送 | 推送后第 37 次外部门槛 CI 绿（Trivy 门收缩态全服务首跑）；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only f701ece..HEAD` 逐条比对）

C-01 实施笔（恰 7 文件）：

- `pom.xml`
- `gateway-service/pom.xml`
- `user-service/pom.xml`
- `record-service/pom.xml`
- `verify-service/pom.xml`
- `leaderboard-service/pom.xml`
- `.trivyignore`

C-02 台账笔（恰 4 文件）：

- `spec/changes/harden-dep-critical-pin/tasks.json`
- `work/mailbox/tasks/TASK-196/spec.md`
- `work/mailbox/tasks/TASK-196/handoff.md`
- `work/mailbox/PLAN.md`

零触碰清单遵守：业务与测试源码、ci.yml、docker-compose*、scripts/verify/*、web/、sql/、common 与 mapmatch 模块 pom 全程零触碰。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **Tomcat 选型版本 10.1.58 修正为 10.1.59**：任务书预注册版本 `10.1.58` 因 Apache 投票未过从未在 Maven Central 发布；实际采用官方公开包含全部 7 项安全修复的 `10.1.59`。实测 7 项 CRITICAL CVE 全消。 | 外部发布状态差异，采用官方已发行的公开等价/超集修复版本，符合 Trivy `>=10.1.58` 判定。 |
| D2 | **fastjson 传递依赖排除无需引入桥接包**：对 rocketmq-spring-boot-starter 与 sentinel 排除 fastjson 后，本地全量 569 用例及 MQ 单测全绿，无 `NoClassDefFoundError`，按任务书裁决不引入 `fastjson-to-fastjson2` 桥接包。 | 符合任务书「若出现类缺失才兜底引入」预注册判定，保持工程依赖最小化。 |
| D3 | **Spring Framework 6.1.29 属商业支持专有包**：Spring 6.1.x 开源 EOL 于 6.1.21，6.1.29 为 VMware 商业付费支持专属版本，Maven Central 仓库不存在（开源修复版本为 7.0.9，违背 Boot 3.2.4 基线 ADR-0001），触发停止条件 §3-7。经提报用户裁决，选定推荐方案：`.trivyignore` 保留 Spring 2 项（CVE-2026-47892 与 CVE-2026-47884）并登记偏差，其余 11 项正常消除并收缩。 | 触发停止条件 §3-7 并由用户显式拍板裁决；如实留痕登记。 |
| D4 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希。处置：§8 列出派发笔 `f701ece`、C-01 `4ddd632` 显式哈希；C-02 自指为台账收口笔，其显式哈希以本回传报告给出（沿 TASK-182~195 先例）。 | 台账提交表终态化固有的单一自指；提交主题与任务书 §4 逐字一致。 |

## 5. 实施证据（含三阶段流水线与验收要点）

### 5.1 三阶段 Pin 形态与选型实证

1. **阶段 1（Tomcat & Netty）**：
   - 父 `pom.xml` 的 `dependencyManagement` 中，在 `spring-boot-dependencies` import 之前通过 First-Declaration-Wins 显式声明：
     - `io.netty:netty-bom:4.1.137.Final`（`pom` / `import`）
     - `org.apache.tomcat.embed:tomcat-embed-core:10.1.59`（`jar`）
   - 实证结果：
     - `sport-verify-user-service` 等镜像构建后，Trivy 扫描 Tomcat 7 项 CRITICAL CVE（CVE-2025-48989, CVE-2025-48988, CVE-2025-48987, CVE-2025-49132, CVE-2025-48986, CVE-2025-48985, CVE-2025-48984）全部清零。
     - Netty 1 项 CRITICAL CVE（CVE-2026-75595）清零。
     - `mvn dependency:tree` 实测全仓 netty 构件全部统一锁定于 4.1.137.Final，无 4.1.107 残留，无版本分裂。
2. **阶段 2（BouncyCastle）**：
   - 父 `pom.xml` 的 `dependencyManagement` 显式声明：
     - `org.bouncycastle:bcprov-jdk18on:1.85`（`jar`）
   - 实证结果：
     - Trivy 扫描 6 镜像中 BouncyCastle 2 项 CRITICAL CVE（CVE-2025-14813, CVE-2026-8763）全部清零。
     - 重点认证域单测（JWT 解析、BCrypt 密码哈希）全绿。
3. **阶段 3（fastjson 传递依赖根除）**：
   - 依赖排查实证：fastjson 非 common 依赖。
     - `user-service`, `record-service`, `verify-service`, `leaderboard-service` 四模块 pom 中对 `rocketmq-spring-boot-starter` 添加 `com.alibaba:fastjson` 的 exclusions。
     - `gateway-service` pom 中对 `spring-cloud-alibaba-sentinel-gateway` 添加 `com.alibaba:fastjson` 的 exclusions。
   - 实证结果：
     - 消除 fastjson 唯一 1 项 CRITICAL CVE（CVE-2022-25845）。
     - `mvn dependency:tree` 实证全仓 fastjson 节点计数为 0。
     - 离线 569 用例及 MQ 单测全绿，无类缺失，未引入桥接包。

### 5.2 .trivyignore 收缩前后行数与内容对照

- **开工态**：46 行，登记 13 项 CVE 及其所属组件分组注释。
- **收缩后终态**：16 行，保留顶部规则说明注释，以及经用户拍板保留的 2 项 Spring 商业专属 CVE（CVE-2026-47892 与 CVE-2026-47884）。
- **收缩差额**：净删除 30 行（11 项 CVE 条目与 4 组分组注释及空行）。

### 5.3 收口 Trivy 门禁跑次读数

执行命令：
`docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd)/.trivyignore:/.trivyignore:ro -v trivy-cache:/root/.cache aquasec/trivy:0.60.0 image --severity CRITICAL --exit-code 1 --ignore-unfixed --ignorefile /.trivyignore <image>`

实测结果：
- `sport-verify-gateway-service:latest`：CRITICAL: 0（未豁免），退出码 0。
- `sport-verify-user-service:latest`：CRITICAL: 0（未豁免），退出码 0。
- `sport-verify-record-service:latest`：CRITICAL: 0（未豁免），退出码 0。
- `sport-verify-verify-service:latest`：CRITICAL: 0（未豁免），退出码 0。
- `sport-verify-leaderboard-service:latest`：CRITICAL: 0（未豁免），退出码 0。
- `sport-verify-mapmatch-service:latest`：CRITICAL: 0（未豁免），退出码 0。
全 6 镜像门禁跑次全绿（全部 rc=0）。

## 6. 逐门实测表

| 门禁项 | 命令 / 方式 | 实测读数与结果 | 判定 |
| --- | --- | --- | --- |
| offline 569 逐位 | `scripts/verify/mvn-verify.sh --mode=offline test` | `36/44/127/137/149/64/12` = 569，Failures: 0, Errors: 0, Skipped: 0，rc=0 | PASS |
| static record-service | `scripts/verify/mvn-verify.sh --static=record-service` | Checkstyle 0 违规，SpotBugs 18 Medium (failThreshold=High)，PMD 0 违规，rc=0 | PASS |
| static leaderboard-service | `scripts/verify/mvn-verify.sh --static=leaderboard-service` | Checkstyle 0 违规，SpotBugs 0 违规，PMD 0 违规，rc=0 | PASS |
| 6 镜像 Docker 构建 | `docker compose -f docker-compose.yml -f docker-compose.services.yml build <6服务>` | 6 镜像全部 BUILD SUCCESS，rc=0 | PASS |
| Trivy 收缩门禁 | `aquasec/trivy:0.60.0` 门禁扫描 6 镜像 | 6 镜像未豁免 CRITICAL 均为 0，全部 rc=0 | PASS |
| 契约门在途态 | `scripts/verify/mailbox-contract.sh --open=TASK-196 --baseline=f701ece` | 在途声明核验通过，rc=0 | PASS |
| 契约门收口态 | `scripts/verify/mailbox-contract.sh` | 无参在途检查通过，rc=0 | PASS |
| 受保护 token 29 项 | `python .trae/tmp/calc_tokens.py`（repo 全量 `git grep -cF`） | 开工 2030，C-01 2030，收口 2030（只增不减持平） | PASS |
| 只改清单与零越界 | `git diff --name-only f701ece..HEAD` | C-01 恰 7 文件，C-02 恰 4 文件，无白名单外触碰 | PASS |
| 差异与换行核验 | `git diff --check` | 无空白/行尾异常，各 pom 保持既有行尾，rc=0 | PASS |

## 7. 受保护 token（29 项核算）

- 29 项既有集合完全锁定，未引入新 token，未修改既有 token。
- 开工实测：SUM=2030
- C-01 实测：SUM=2030
- 收口复测：SUM=2030
- 新增台账文档中严格禁止枚举任何 29 项 token 字面量，仅记录总和与机制。

## 8. 提交表

| 序号 | 提交类型与主题 | 显式哈希 | 文件清单 | 说明 |
| --- | --- | --- | --- | --- |
| 0 | 派发笔 | `f701ecedadc429f8f2f2f6027898a93345572898`（`f701ece`） | 4 文件 | 任务书与 spec/changes 三件套派发 |
| 1 | C-01 实施笔 | `4ddd6321584294a609ae6906fa74986417daaa64`（`4ddd632`） | 7 文件 | pom Pin 版本 + fastjson exclusions + .trivyignore 收缩 |
| 2 | C-02 台账笔 | `（本笔自指：显式哈希以回传报告给出）` | 4 文件 | tasks.json + spec.md + handoff.md + PLAN.md |

当前分支拓扑：`origin/main...main` 计数为 `0 4`（保持未推送状态）。

## 9. 未覆盖项与后续基线

- **未覆盖项**：
  - Spring Framework 的 2 项 CVE（CVE-2026-47892 与 CVE-2026-47884）因属于 VMware 商业付费专有支持补丁包（开源版本仅 7.x 修复，违背 Boot 3.2.4 基线 ADR-0001），经用户明确拍板保留在 `.trivyignore`，未在本次开源升级中覆盖。
- **后续基线**：
  - 全仓依赖中 Netty 统一为 4.1.137.Final，Tomcat Embed Core 统一为 10.1.59，BouncyCastle 统一为 1.85，各模块不再携带 fastjson 传递依赖。
  - `.trivyignore` 当前维持 16 行。
  - CI 第 37 次触发时将验证收缩后的依赖扫描门。
