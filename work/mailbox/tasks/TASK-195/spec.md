# 任务书：F21 CI 依赖漏洞扫描与全服务镜像构建门（TASK-195 / add-ci-dep-scan-image-gates）

唯一指令源：本任务书 + spec/changes/add-ci-dep-scan-image-gates/（proposal.md / tasks.json / specs/sport-record-verify/spec-delta.md）。冲突时以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文（中文检索用 Grep/Read）；唯一验证入口 scripts/verify/mvn-verify.sh（禁手跑 mvn）；退出码 0/1/3/2，3=依赖源未定不得记 PASS。

## §0 红线（十条，违反即 FAILED）

1. **白名单制改动面**：C-01 仅 `.github/workflows/ci.yml`（分支 B 触发时 + repo 根 `.trivyignore` 新文件）；C-02 恰 5 台账文件（见 §4）。其余零触碰：业务代码、父 pom 与各模块 pom、docker-compose*、scripts/verify/*、web/、sql/、六服务源码与配置。
2. **禁引入新 Maven 插件**：全部 pom 零改动；依赖漏洞扫描仅走 CI 步骤级（Trivy 固定版本，禁浮动 tag / @master / @latest）。
3. **既有 CI 步骤零语义改动**：Build and test（online verify）/ Provision placeholder env / compose parse check / Static analysis gate / Public docs wording self-check / Upload JaCoCo report / web 档三步全部保持；镜像构建步骤为原「Build representative service image」的就地扩展（不新增平行重复步骤）。
4. **词面门口径（本课题特殊）**：ci.yml 属词面门豁免路径（正则定义所在，整文件沿 CI exclude 口径），但**新增行零禁词**（diff 新增行逐行自检）；其余改动文件（.trivyignore / 台账）常规 ZERO_HIT；禁词正则与受保护 token 字面量均不得写入新文档。
5. **扫描基线决策树**（首跑实测后按分支落地，禁擅自变更）：A=0 CRITICAL → CRITICAL 阻断；B=存量 >0 → .trivyignore 登记存量指纹（纯追加、逐条 CVE ID + 镜像 + 来源注释）+ 同门槛，零依赖升级；C=DB/工具不可用 → **停手回报**，禁 continue-on-error 静默跳过。
6. **零业务代码改动的硬门**：offline 全量 569（36/44/127/137/149/64/12）逐位不变 + `--static=record-service` rc=0 不变（执行侧本地复跑留证）。
7. **零收益数字 claim**：扫描命中数、安全/质量改善数字不写入任何 tracked 文档；只登记机制性事实（步骤存在、扫描范围、基线计数、版本号）。
8. **提交卫生**：ci.yml 修改保持既有行尾（开工 `git ls-files --eol` 实测登记）；新文件 LF + 末尾换行；`git diff --check` 干净；两笔提交格式 `type(scope): description (TASK-195)`。
9. **token 29 项只增不减**：开工实测（TASK-194 收口真值 2030 参照）→ 收口复测；新文档禁枚举字面量。
10. **推送纪律**：两笔本地提交均不推送不建 PR（push 由指导侧授权）；CI 红禁重试刷绿、按签名归因。

## §1 侦察实证（指导侧已核，开工不必重跑，台账引用本节即可）

- findings-summary.md:118 F21 原文；:196-197 TASK-192 盘点段「静态检查门已在；依赖漏洞扫描与镜像构建步骤仍缺」；:213-214/225-227 订正后 P2 尾巴仅剩 F21（F23 已订正关闭，F11/F12/F13 由 TASK-193 收口）。
- ci.yml 现状：build 档步骤链（Build and test 8m → Provision placeholder env（`cp scripts/verify/env.example .env`）→ compose parse check → Build representative service image（仅 leaderboard-service，注释声明单代表口径）→ Static analysis gate（leaderboard-service，811 持平门）→ Public docs wording self-check → Upload JaCoCo）；web 档三步。
- docker-compose.services.yml 六服务 build 定义齐全（均 `context: .` + `<service>/Dockerfile`）：gateway:22 / user:49 / record:79 / verify:116 / leaderboard:148 / mapmatch:180。
- 仓库无 .github/dependabot.yml；无既有依赖扫描手段。
- TASK-194 后基线：offline 569 逐位、static rc=0 全绿、token SUM=2030、外部门槛 35 次（34 绿 1 红）。
- 方案取舍（排除项与理由）见 proposal.md「方案取舍」节。

## §2 预注册设计

### §2.1 全服务镜像构建步骤（R1）

原「Build representative service image」步骤就地扩展：

- 命令：`docker compose -f docker-compose.yml -f docker-compose.services.yml build gateway-service user-service record-service verify-service leaderboard-service mapmatch`（显式列 6，与 compose 定义集恰好全等）
- 步骤更名（如 `Build all service images (6/6)`）+ 注释更新：6/6 全覆盖口径；与 compose parse 分工（config 验结构与插值、build 实测 Dockerfile 构建链）；替换原「单代表 + 台账登记」注释
- 保持该步骤与 Provision env 的前置关系（.env 占位为 compose 命令前置依赖）

### §2.2 Trivy 依赖漏洞扫描步骤（R2）

- 位置：镜像构建步骤之后（消费其产物）；Static analysis gate 之前
- 形态二选一（选型与理由登记 handoff）：甲=`aquasecurity/trivy-action@<固定 tag>`（每镜像一步 ×6 或 matrix）；乙=bash 安装 trivy CLI 固定版本 + for 循环扫 6 镜像单步骤
- 镜像名口径：以本地 `docker images` 实测为准（预期 `sports-<service>` 形态），CI 与本地口径一致登记
- 输出口径：`--severity HIGH,CRITICAL` 全量输出登记（台账记每镜像计数）；阻断口径按 §2.3 落地
- Trivy DB 下载为 CI 每次联网行为（机制性事实登记；可选 actions/cache 加速，非必须）

### §2.3 首跑基线决策树（执行侧本地首跑 → 分支落地）

本地首跑：`docker compose ... build` 六服务 → trivy 扫 6 镜像（`--severity HIGH,CRITICAL`）→ 读数：

- **分支 A（0 CRITICAL）**：CI 门槛 = `--severity CRITICAL --exit-code 1`（ignore-unfixed 口径实测后定并登记理由，预注册倾向：阻断跑次加 `--ignore-unfixed`，输出跑次不加）
- **分支 B（CRITICAL > 0）**：repo 根新建 `.trivyignore`，逐条登记存量指纹（`CVE-<id>` + 注释：镜像、来源、登记时点，纯追加治理）；CI 门槛同分支 A；存量修复另立课题（本课题零依赖升级、pom 零改动）
- **分支 C（本地 trivy 安装失败 / DB 下载不可用）**：停手回报（见 §3），禁静默降级
- 首跑读数、分支判定、ignore-unfixed 决策、版本号全部登记 handoff §5

### §2.4 台账（R3）

findings-summary.md F21 条目下纯追加核实段（标注时点 + 依据：本课题两步骤落地 + 第 36 次 CI 外部终验口径）；PLAN.md 纯追加验收记录（含 findings 清零声明、CI 步骤清单变更声明、后续任务书基线说明）。

## §3 停止条件（触发即停手回报，禁自行处置）

1. 本地六服务镜像构建任一失败（Dockerfile/编排漂移属真发现，修复超白名单，由用户裁决扩权或另立课题）
2. 本地 Trivy 安装或 DB 下载不可用（决策树分支 C）
3. 首跑出现需升级依赖才能消除的 CRITICAL（走分支 B 登记，禁擅自升级）
4. 既有 CI 步骤需语义改动才能适配新步骤（如步骤依赖/顺序冲突）
5. 词面门（新增行口径）/ 契约门 / offline / static 任一异常
6. 需触碰 §0-1 白名单外任何文件

## §4 白名单（只改清单）

- **C-01 实施笔**（1-2 文件）：`.github/workflows/ci.yml`；（仅分支 B）`.trivyignore`（repo 根，新文件）
- **C-02 台账笔**（恰 5 文件）：`spec/changes/add-ci-dep-scan-image-gates/tasks.json`（全勾翻转）、`work/mailbox/tasks/TASK-195/spec.md`（§7 占位回填）、`work/mailbox/tasks/TASK-195/handoff.md`（新建）、`work/mailbox/PLAN.md`（纯追加）、`work/mailbox/findings-summary.md`（F21 核实标注纯追加）
- 提交主题：C-01 `ci(build): 全服务镜像构建门与 Trivy 依赖漏洞扫描（TASK-195）`；C-02 `docs(mailbox): 登记 TASK-195 CI 增补验收与 F21 收口台账闭环（TASK-195）`

## §5 受保护 token（29 项）

开工实测 SUM（预期 2030 持平）→ 收口复测只增不减；29 项清单见 TASK-187 handoff §7；字面量禁写入新文档。

## §6 门禁结构

- **开工规程**：基线 HEAD=派发笔（git log 取哈希登记）、工作区净、`git ls-files --eol` 登记 ci.yml 行尾、token 开工实测、offline 569 基线确认（TASK-194 台账引用即可，异常才重跑）
- **预提交门禁**（C-01/C-02 前）：python yaml.safe_load 解析 ci.yml rc=0；词面门（ci.yml 新增行口径 + 其余改动文件四形态 ZERO_HIT + 探针三态）；`git diff --check` 干净；行尾核验
- **收口门禁**：offline 569 逐位不变 + static rc=0（mvn-verify.sh 亲跑留证）；契约门双态（`mailbox-contract.sh --open TASK-195 --baseline=<派发笔哈希>` 与无参）rc=0；token 复测；只改清单全等（`git diff --name-only <派发笔>..HEAD` 恰白名单）；零越界（pom/compose/scripts/业务源码/测试源码零改动）；台账纯追加（PLAN/findings 删除列 0；tasks.json 勾选翻转除外）
- **外部终验**：push 后第 36 次 CI 全绿（新两步骤生效首跑），红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`eefea9756a1678079623aac79dde7cf91cff2134`（`eefea97`）
- C-01 实施笔：`282475b92061588b99ae30da8d96abeb62465711`（`282475b`）
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）`

### §7.2 实施读数

- **决策树分支判定**：分支 B（CRITICAL > 0，首跑实测命中 13 项存量 CRITICAL 级别已知 CVE）。
- **CRITICAL 与 HIGH 计数**（首跑实测）：
  - `sport-verify-gateway-service:latest`：CRITICAL=4，HIGH=44
  - `sport-verify-user-service:latest`：CRITICAL=12，HIGH=89
  - `sport-verify-record-service:latest`：CRITICAL=12，HIGH=89
  - `sport-verify-verify-service:latest`：CRITICAL=12，HIGH=89
  - `sport-verify-leaderboard-service:latest`：CRITICAL=12，HIGH=89
  - `sport-verify-mapmatch-service:latest`：CRITICAL=10，HIGH=45
  - 全仓 6 服务合计：CRITICAL 累计 62 次（去重 13 项唯一 CVE），HIGH 累计 445 次。
- **.trivyignore 条目数**：13 项（纯追加登记于 repo 根 `.trivyignore`，逐条注明影响镜像、来源及登记时点）。
- **Trivy 版本与选型**：选型形态 乙（bash 安装 Trivy CLI 固定版本 v0.60.0，从官方镜像 `aquasec/trivy:0.60.0` 提取 `/usr/local/bin/trivy` 二进制）+ for 循环扫 6 镜像单步骤；理由见 handoff §5.4。
- **ignore-unfixed 决策**：CI 门禁阻断跑次增加 `--ignore-unfixed`（结合 `.trivyignore` 存量白名单双重把关，确保门禁仅拦截具备上游可修复版本且未豁免的真实风险漏洞，避免上游不可修复 0-day 抖动破坏构建流水线）；输出跑次不加（全量呈现 CVE 态势）。
- **镜像名口径**：本地与 CI 完全对齐，均为 `sport-verify-<service>:latest`（基于 `docker-compose.yml` 显式 `name: sport-verify` 项目名）。

### §7.3 门禁读数

- **yaml 校验**：`python yaml.safe_load` 解析 ci.yml rc=0。
- **词面门**：ci.yml 新增 27 行逐行自检零禁词；其余改动文件全量 ZERO_HIT rc=1；探针三态正常通过。
- **offline 全量**：`mvn-verify.sh --mode=offline test` 结果 `36/44/127/137/149/64/12` = 569 逐位不变，rc=0，BUILD SUCCESS。
- **static 静态门**：`mvn-verify.sh --static=record-service` Checkstyle 0 违规，SpotBugs 18 Medium（0 High，failThreshold=High），PMD 0 违规，rc=0 全绿。
- **契约门双态**：在途 `--open TASK-195 --baseline=eefea97` rc=0（判据 A 两件套齐 + 1 待办放行 + 判据 B 清单一致）；收口无参预期 rc=0。
- **token 29 项**：开工 SUM=2030；C-01 后 SUM=2030；收口复测持平，只增不减。
- **只改清单全等**：C-01 恰 2 文件（ci.yml、.trivyignore）；C-02 恰 5 文件（tasks.json、spec.md、handoff.md、PLAN.md、findings-summary.md）；零越界。
- **diff --check**：`git diff --check` 干净。
- **行尾**：ci.yml 保持原行尾；新文件 pure LF + 末尾换行。
