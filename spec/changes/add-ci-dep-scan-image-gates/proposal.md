# 提案：F21 CI 依赖漏洞扫描与全服务镜像构建门（add-ci-dep-scan-image-gates / TASK-195）

## 背景与问题

findings F21（CI 缺口）经 TASK-192 盘点确认为「部分」：静态检查门已落地（leaderboard TASK-018 + record TASK-194，全仓 rc=0），剩余两缺口：

1. **依赖漏洞零可见性**：CI 无任何依赖漏洞扫描步骤，六服务依赖树（Spring Boot 等）的已知 CVE 完全不可见
2. **镜像构建仅 1/6 覆盖**：ci.yml 只实际构建 leaderboard-service 一个代表镜像，其余 5 服务（gateway/user/record/verify/mapmatch）的 Dockerfile 与编排/模块结构漂移仅被 `compose config` 静态解析覆盖，从未实际构建验证

F21 是 findings 最后一项（TASK-193 已收 F11/F12/F13，F23 订正关闭），收口即 findings 全清零。

## 方案

- **R1 全服务镜像构建门**：现「Build representative service image（仅 leaderboard-service）」步骤就地扩展为全部 6 服务实际构建（gateway/user/record/verify/leaderboard/mapmatch），任一失败即 CI 红
- **R2 依赖漏洞扫描门**：镜像构建后以 Trivy（固定版本）扫描 6 个已构建镜像（镜像内含全部运行时依赖 jar，等效依赖漏洞可见性）。阻断策略按首跑实测基线决策树：
  - 分支 A（首跑 0 CRITICAL）：门槛 CRITICAL 阻断（exit 1）
  - 分支 B（存量 CRITICAL > 0）：`.trivyignore` 登记存量指纹（纯追加、逐条注明），门槛仍 CRITICAL exit 1，即「不劣于基线」；存量修复另立课题（本课题零依赖升级、pom 零改动）
  - 分支 C（Trivy DB 下载/扫描不可用）：停手回报，禁静默跳过（禁 continue-on-error）
- **R3 findings 收口登记**：F21 条目补核实标注（纯追加、标注时点），findings 清零登记进 PLAN.md

## 方案取舍（已排除）

- **OWASP dependency-check-maven（pom 插件）**：「禁引入新 Maven 插件」红线排除（两次提案明确拒绝先例）
- **OWASP dependency-check CLI**：不污染 pom 但依赖 NVD API key，无 key 时 CI 漏洞库下载超时风险高，排除
- **actions/dependency-review-action**：不支持 Maven pom 清单，排除
- **Dependabot**：非 CI 步骤、alerts 依赖仓库设置层、version-update PR 与 main 直推工作流摩擦，列为备选不入本课题

## 影响面与边界

- 改动面：`.github/workflows/ci.yml`（C-01 唯一必改文件；分支 B 时增 `.trivyignore` 新文件）+ 台账（C-02 五文件，含 findings F21 标注）
- 零触碰：业务代码 / 父 pom 与各模块 pom / docker-compose* / scripts/verify/* / web / sql / 六服务源码
- CI 影响（机制性事实）：build 档时长增加（6 镜像构建 + Trivy DB 下载扫描）；第 36 次 CI 起生效
- 零收益数字 claim：只登记步骤存在性、扫描范围、基线计数等机制性事实
