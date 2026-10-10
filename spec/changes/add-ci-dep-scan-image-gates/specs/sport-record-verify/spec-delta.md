# spec-delta：F21 CI 依赖漏洞扫描与全服务镜像构建门（TASK-195 add-ci-dep-scan-image-gates）

## ADDED 需求：R1 全服务镜像构建门

CI build 档 SHALL 实际构建 docker-compose.services.yml 声明的全部 6 个服务镜像（gateway-service/user-service/record-service/verify-service/leaderboard-service/mapmatch-service），任一构建失败即 CI 失败。现「Build representative service image（仅 leaderboard-service 单代表）」步骤就地扩展（同步骤改命令），步骤名与注释更新为 6/6 全覆盖口径，并注明与 `compose config` 静态解析检查的分工：config 验结构与插值、build 实测 Dockerfile 构建链。

## ADDED 需求：R2 依赖漏洞扫描门（Trivy + 首跑基线决策树）

CI SHALL 在镜像构建后以 Trivy 固定版本（禁浮动 tag）扫描 6 个已构建镜像（镜像内含全部运行时依赖 jar，等效依赖漏洞可见性）。阻断策略按首跑实测基线决策树预注册：

- 分支 A（首跑 0 CRITICAL）：门槛 CRITICAL 阻断（severity CRITICAL + exit-code 1），HIGH 及以下计数登记不阻断
- 分支 B（存量 CRITICAL > 0）：repo 根新增 `.trivyignore`（纯追加治理，逐条 CVE 指纹 + 来源注释），门槛仍 CRITICAL 阻断，即「不劣于基线」；存量修复另立课题，本课题零依赖升级、pom 零改动
- 分支 C（Trivy DB 下载或扫描本身不可用）：停手回报，禁 continue-on-error 静默跳过

ignore-unfixed 口径（不可修复漏洞是否计入阻断）由执行侧实测后定并登记理由。扫描输出与基线计数登记台账，零安全收益数字 claim。

## MODIFIED 需求：CI build 档步骤清单

build 档步骤由「Build and test → Provision placeholder env → compose parse check → Build representative service image → Static analysis gate → Public docs wording self-check → Upload JaCoCo report」变更为「…compose parse check → **Build all service images（6 服务）** → **Trivy dependency scan** → Static analysis gate → …」。既有步骤语义零改动；Provision placeholder env 前置关系保持（镜像构建依赖 .env 占位）。

## MODIFIED 需求：findings F21 收口

F21（CI 缺口）经本课题后三项缺口全消（静态门 TASK-018/194、镜像构建与依赖扫描本课题），findings-summary.md F21 条目补核实标注（纯追加、标注时点），findings 全表清零，状态登记进 PLAN.md。

## 验收断言

- ci.yml 含 6 服务显式构建命令与 Trivy 扫描步骤（固定版本、6 镜像全覆盖、决策树分支落地、无 continue-on-error）
- offline 569（36/44/127/137/149/64/12）逐位不变 + static rc=0 不变（零业务代码改动硬门）
- 只改清单：C-01 恰 1 文件（ci.yml；分支 B 时 +1 .trivyignore）；C-02 恰 5 文件（tasks.json / 任务书 §7 / handoff.md / PLAN.md / findings-summary.md）
- 零触碰：业务代码 / 父 pom 与各模块 pom / docker-compose* / scripts/verify/* / mvn-verify.sh / mailbox-contract.sh / web / sql
- 词面门：ci.yml 整文件沿 CI exclude 豁免口径（定义所在），新增行零禁词；其余改动文件 ZERO_HIT
- token 29 项只增不减；台账纯追加；第 36 次 CI 全绿为外部终验
