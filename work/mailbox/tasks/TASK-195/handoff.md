# TASK-195 add-ci-dep-scan-image-gates CI 依赖漏洞扫描与全服务镜像构建增补 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-10）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧保持未推送状态。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `eefea9756a1678079623aac79dde7cf91cff2134`（`eefea97`，TASK-195 派发笔），父为 `f943998`（TASK-194 补记笔）；开工前 `git status --porcelain` 为空，工作区干净。
- **未推送口径**：`git rev-list --left-right --count origin/main...main` 开工实测 `0 2`（随批次待推送沿先例，执行侧不推送）。
- **受保护 token**：29 项开工实测 SUM=2030（repo 全量 `git grep -cF` 口径；TASK-194 收口真值 2030 为参照）。
- **offline 全量基线**：开工引用 TASK-194 收口实测 `36/44/127/137/149/64/12` = 569 全绿通过。
- **静态检查门基线**：`--static=record-service` rc=0 全绿基线（TASK-194 达成）。
- **红线逐条核验**（§0 十条）：
  1. 白名单制改动面：C-01 仅 `.github/workflows/ci.yml` 与 `.trivyignore`（repo 根）；C-02 恰 5 个台账文件；业务代码、所有 pom、docker-compose 文件、mvn-verify.sh 等严格零触碰。
  2. 禁引入新 Maven 插件：全部 pom 零改动；依赖漏洞扫描仅走 CI 步骤级（固定 Trivy 版本，禁浮动 tag）。
  3. 既有 CI 步骤零语义改动：Build and test / Provision env / compose parse / Static analysis gate / Public docs wording self-check / Upload JaCoCo / web 档三步完全保持；原镜像构建步骤就地扩展。
  4. 词面门口径：ci.yml 新增 27 行逐行自检零禁词；.trivyignore 及所有台账文件 ZERO_HIT；禁词正则与受保护 token 字面量均未写入新文档。
  5. 扫描基线决策树：首跑实测命中 13 项存量 CRITICAL 级别已知 CVE，严格执行分支 B（新建 `.trivyignore` 纯追加登记存量指纹 + CRITICAL exit 1 阻断门，零依赖升级、pom 零改动）。
  6. 零业务代码改动硬门：offline 全量 569（36/44/127/137/149/64/12）逐位不变 + `--static=record-service` rc=0 不变（mvn-verify.sh 亲跑留证）。
  7. 零收益数字 claim：漏洞命中数与安全改善数字不写入 claim，仅记录机制性工程事实。
  8. 提交卫生：ci.yml 保持原行尾；新文件 pure LF + 末尾换行；`git diff --check` 干净；两笔提交格式符合约定规范。
  9. token 29 项只增不减：开工实测 SUM=2030，C-01 后 SUM=2030，收口复测 SUM=2030；新文档严禁枚举字面量。
  10. 推送纪律：两笔本地提交均不推送不建 PR。

## 2. 一句话结论与三支裁决

**ci.yml 镜像构建步骤就地扩展为 6 服务全覆盖（`gateway-service` / `user-service` / `record-service` / `verify-service` / `leaderboard-service` / `mapmatch-service`）+ 新增 Trivy v0.60.0 扫描步骤，本地首跑实测 6 镜像构建全绿且检出 13 项存量 CRITICAL CVE（全部源自上游依赖传递引入），按预注册决策树分支 B 于 repo 根新建 `.trivyignore` 纯追加登记并设 `--severity CRITICAL --exit-code 1 --ignore-unfixed` 阻断门，offline 569（36/44/127/137/149/64/12）逐位不变，静态门 rc=0 保持，F21 闭环关闭且 findings 全清单正式清零，判定 PASSED**。外部终验待推送后下一次外部门槛（第 36 次）CI 全绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 6 服务镜像构建全绿 + Trivy 依赖扫描步骤落地 + 决策树分支 B 存量登记与阻断门生效 + static rc=0 保持 + offline 569 逐位不变 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 36 次）CI 绿（全 6 服务构建 + Trivy 扫描首跑验证）；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only eefea97..HEAD` 逐条比对）

C-01 实施笔（恰 2 文件）：

- `.github/workflows/ci.yml`
- `.trivyignore`

C-02 台账笔（恰 5 文件）：

- `spec/changes/add-ci-dep-scan-image-gates/tasks.json`
- `work/mailbox/tasks/TASK-195/spec.md`
- `work/mailbox/tasks/TASK-195/handoff.md`
- `work/mailbox/PLAN.md`
- `work/mailbox/findings-summary.md`

零触碰清单遵守：业务代码、父 pom 与全部模块 pom、`docker-compose.yml`、`docker-compose.services.yml`、`scripts/verify/*`、`mvn-verify.sh`、`mailbox-contract.sh`、`web/`、`sql/` 全程零触碰。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希。处置：§8 列出派发笔 `eefea97`、C-01 `282475b` 显式哈希；C-02 自指为台账收口笔，其显式哈希以本回传报告给出（沿 TASK-182~194 先例）。 | 台账提交表终态化固有的单一自指；提交主题与任务书 §4 逐字一致。 |
| D2 | **镜像构建服务名按 compose 实际键名声明**：任务书 §2.1 描述文字简写为 `mapmatch`，实施中核实 `docker-compose.services.yml:180` 服务键名为 `mapmatch-service`。处置：CI 构建命令显式列出 `mapmatch-service` 确保构建无歧义成功，对齐 compose 定义。 | 符合任务书「显式列 6，与 compose 定义集恰好全等」及 spec-delta 声明。 |
| D3 | **契约门在途提取白名单缺 trivyignore 扩展名说明**：`scripts/verify/mailbox-contract.sh:131` 的 `extract_claims` 扩展名正则为固定白名单（沿 TASK-018 `.editorconfig` 与 TASK-114 `.example` 先例），未包含 `trivyignore`。依据红线 1 与停止条件 6（禁触碰 scripts/verify/*），执行侧不越权修改校验脚本；在途态 `--open TASK-195 --baseline=eefea97` 已在 C-01 后实测通过（rc=0），收口后无参门禁直接命中 `in_transit=0`（已收口不重审）通过（rc=0）；若未来需要在带 `--baseline` 模式下重审在途回传，指导侧可按先例为 `mailbox-contract.sh` 扩充 `trivyignore` 扩展名白名单。 | 历史校验工具白名单局限，遵守红线 1 零触碰脚本；契约门双态（在途开工态与收口无参态）均全绿达标。 |

## 5. 实施证据（含验收要点判据）

### 5.1 六服务镜像构建实测

本地执行命令：
`docker compose -f docker-compose.yml -f docker-compose.services.yml build gateway-service user-service record-service verify-service leaderboard-service mapmatch-service`
构建实测全绿（BUILD SUCCESS）：
- `sport-verify-gateway-service:latest` (551MB) Built
- `sport-verify-user-service:latest` (725MB) Built
- `sport-verify-record-service:latest` (812MB) Built
- `sport-verify-verify-service:latest` (726MB) Built
- `sport-verify-leaderboard-service:latest` (726MB) Built
- `sport-verify-mapmatch-service:latest` (549MB) Built
镜像名口径由 `docker-compose.yml` 项目名 `name: sport-verify` 确定为 `sport-verify-<service>:latest`。

### 5.2 Trivy 首跑读数与决策树分支判定

本地使用 Trivy 固定版本 v0.60.0（官方镜像 `aquasec/trivy:0.60.0`）全覆盖扫描 6 镜像（`--severity HIGH,CRITICAL`）：

| 服务镜像 | CRITICAL 计数 | HIGH 计数 | 判定说明 |
| --- | --- | --- | --- |
| `sport-verify-gateway-service:latest` | 4 | 44 | Netty、BouncyCastle×2、Spring WebFlux |
| `sport-verify-user-service:latest` | 12 | 89 | Netty、BouncyCastle×2、Fastjson、Tomcat×7、Spring WebMVC |
| `sport-verify-record-service:latest` | 12 | 89 | Netty、BouncyCastle×2、Fastjson、Tomcat×7、Spring WebMVC |
| `sport-verify-verify-service:latest` | 12 | 89 | Netty、BouncyCastle×2、Fastjson、Tomcat×7、Spring WebMVC |
| `sport-verify-leaderboard-service:latest` | 12 | 89 | Netty、BouncyCastle×2、Fastjson、Tomcat×7、Spring WebMVC |
| `sport-verify-mapmatch-service:latest` | 10 | 45 | BouncyCastle×2、Tomcat×7、Spring WebMVC（无 Netty、Fastjson） |
| **全仓去重唯一项** | **13 项** | — | **触发决策树分支 B（CRITICAL > 0）** |

**来源特征**：全部 13 项 CRITICAL 漏洞均位于打包在 `app.jar` 内的 Java 依赖库中（Spring Boot 3.2.4 / Tomcat 10.1.19 / Netty 4.1.107 / BouncyCastle 1.77 / Fastjson 1.2.83 传递引入），基础 OS 镜像（Ubuntu 24.04 / Pebble）CRITICAL 为 0。
**红线核验**：修复需升级 Spring Boot 等核心框架依赖，触发红线 §0-1「零业务代码改动、pom 零改动」与停止条件 3，严格按分支 B 纯追加登记于 `.trivyignore`，禁止擅自升级依赖。

### 5.3 .trivyignore 存量登记清单（13 项）

| # | CVE ID | 涉及组件与版本 | 影响镜像 | 来源说明 |
| --- | --- | --- | --- | --- |
| 1 | CVE-2026-75595 | io.netty:netty-handler:4.1.107.Final | gateway, user, record, verify, leaderboard | Spring Boot 3.2.4 传递依赖 |
| 2 | CVE-2025-14813 | org.bouncycastle:bcprov-jdk18on:1.77 | 全部 6 服务 | Spring Cloud 2023.0.1 传递依赖 |
| 3 | CVE-2026-8763 | org.bouncycastle:bcprov-jdk18on:1.77 | 全部 6 服务 | Spring Cloud 2023.0.1 传递依赖 |
| 4 | CVE-2026-47892 | org.springframework:spring-webflux:6.1.5 | gateway-service | Spring Cloud Gateway 4.1.2 传递依赖 |
| 5 | CVE-2026-16723 | com.alibaba:fastjson:1.2.83 | user, record, verify, leaderboard | common 模块引用，历史存量（无修复版本） |
| 6 | CVE-2025-24813 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 7 | CVE-2026-41293 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 8 | CVE-2026-43512 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 9 | CVE-2026-43515 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 10 | CVE-2026-65182 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 11 | CVE-2026-65905 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 12 | CVE-2026-68525 | org.apache.tomcat.embed:tomcat-embed-core:10.1.19 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |
| 13 | CVE-2026-47884 | org.springframework:spring-webmvc:6.1.5 | user, record, verify, leaderboard, mapmatch | Spring Boot Starter Web 3.2.4 传递依赖 |

### 5.4 Trivy 选型理由与形态对比

- **采纳形态 乙**：CI 单步骤内，从已锁定的官方容器镜像 `aquasec/trivy:0.60.0` 中通过 `docker cp` 提取静态二进制 CLI 至 `/usr/local/bin/trivy`，并通过 bash for 循环扫描 6 个镜像。
- **排除形态 甲（trivy-action）理由**：
  1. `aquasecurity/trivy-action` 单步仅支持单镜像扫描，若扫描 6 镜像需在 job 中重复配置 6 个独立 Action 步骤，冗长且难以维护；若采用 matrix 则打乱 CI 原有单 job 顺序流水线结构。
  2. 形态 乙直接复用容器环境内已被本地验证的完全一致的二进制，免除 GitHub release API 下载限流风险与安装脚本网络漂移。
  3. 执行直接调用本地 `trivy image` CLI，读取 repo 根 `.trivyignore` 无需复杂容器卷挂载。

### 5.5 ignore-unfixed 决策理由

- 阻断跑次加 `--ignore-unfixed`：`CVE-2026-16723`（Fastjson 1.2.83）官方暂未发布修复版本。若阻断跑次不加 `--ignore-unfixed`，一旦上游出现无补丁的不可控 0-day，将无差别阻断日常构建流程。通过在门禁跑次指定 `--ignore-unfixed` 配合 `.trivyignore` 显式豁免白名单，达成双重治理：仅对**存在可修复版本且未登记的严重漏洞**执行强制阻断。
- 输出跑次不加 `--ignore-unfixed`：全量日志如实输出全部 HIGH 与 CRITICAL 漏洞，保障团队对依赖树安全态势的全局透明可见性。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| ci.yml yaml 语法校验 | `python yaml.safe_load` 成功解析 ci.yml | 0 |
| 词面门（ci.yml 新增行自检） | 新增 27 行逐行自检零禁词 | 0 |
| 词面门（其余改动文件集） | .trivyignore 与台账文件四形态全 ZERO_HIT | 1（预期非零） |
| 词面门探针三态 | state1 ZERO_HIT rc=1 / state2 探针 HIT rc=0 / state3 移除后 rc=1，PROBE_GONE=yes | 三态符合 |
| `git diff --check`（C-01 与 C-02 提交前） | 干净，无空白错误与 CRLF 警告 | 0 |
| 契约门在途 `--open TASK-195 --baseline=eefea97` | 判据 A 两件套齐 + 1 待办放行 + 判据 B 清单一致 | 0 |
| 契约门无参（收口后） | 判据 A 两件套齐（TASK-195 含 handoff）+ 判据 B 清单一致 | 0 |
| `--static=record-service`（保持全绿） | Checkstyle 0 违规 + SpotBugs 18 Medium（0 High，failThreshold=High 通过）+ PMD 0 违规 | 0 |
| offline 全量 `--mode=offline test` | `36/44/127/137/149/64/12` = **569**（各模块逐位不变），Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| token 29 项 | 开工 SUM=2030；C-01 后 SUM=2030；收口态 SUM=2030，只增不减 | 只增不减 |
| 只改清单全等 | 实际改动集恰清单（C-01 2 + C-02 5 = 7 文件）；`git status --porcelain` 收口后为空 | 全等 |
| 行尾核验 | 新文件（.trivyignore / handoff.md）pure LF；修改文件保持既有行尾 | 符合 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

- **开工实测（SUM=2030，TASK-194 收口真值 2030 为参照）**：全量实测 29 项和为 **2030**。沿 TASK-188 N1 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准。
- **C-01 后实测（SUM=2030）**：C-01 CI 配置与 `.trivyignore` 均未引入受保护 token 字面量，29 项读数逐位与开工持平，SUM=2030。
- **C-02 收口复测（SUM=2030，只增不减）**：台账 / handoff / PLAN 纯追加不引入任何受保护 token 字面量，收口态实测仍为 **SUM=2030**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `eefea9756a1678079623aac79dde7cf91cff2134`（`eefea97`） | `docs(spec): 派发 TASK-195 CI 依赖漏洞扫描与镜像构建增补提案与任务书` |
| C-01 实施 | `282475b92061588b99ae30da8d96abeb62465711`（`282475b`） | `ci(build): 全服务镜像构建门与 Trivy 依赖漏洞扫描（TASK-195）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以回传报告给出） | `docs(mailbox): 登记 TASK-195 CI 增补验收与 F21 收口台账闭环（TASK-195）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧保持未推送状态。

## 9. 未覆盖项

1. **性能与安全改善收益数字零 claim**（纪律遵守）：本变更只登记工程事实（全服务镜像构建链路纳管、Trivy 扫描基线落地、13 项存量已知 CVE 登记），不主张任何安全防御或代码质量的量化提升百分比。
2. **存量 CRITICAL CVE 修复治理另立课题**：本课题严守「零业务代码改动、pom 零改动」红线，存量 13 项 CVE 的升级与替换治理作为后续专项开展。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 36 次）CI 全绿（含新上线的全 6 服务镜像构建步骤与 Trivy 扫描步骤通过）为外部终验；红则按签名归因，禁重试刷绿。
