# 提案：统一验收入口与门槛接线（堵住"绿"的三种来源不一致）

## Why

本轮 Harness 评审（`/better-harness`，窗口 2026-08-22 ~ 2026-09-21，150 会话 / 502 Task Episode）留了 8 条发现，其中 5 条是同一个关注点：**"这次改动算不算通过"没有唯一的、受版本控制的判定入口**，于是同一个问题在三个地方各说各话。

**代码与仓库侧已核对（不是只信台账与交接文档）**：

- `work/mailbox/PLAN.md` 开头记录 2026-09-20 自查：此前所有复跑用 `mvn -B -ntp -pl <模块> -am test`，**漏 `-s .mvn-settings.xml`**，类路径来自系统默认本地仓库；换规范口径 `mvn -B -ntp -o -s .mvn-settings.xml test` 后 leaderboard-service 连依赖都解析不了（lettuce-core、xxl-job-core 缺失）→ **TASK-002/003/005 的"通过"与 37/39/268 等数字一律作废重取**。
- `.mvn-settings.xml:7` 把 `localRepository` 指到仓内 `.m2-repo`，该文件与离线仓**都在忽略清单内**（`.gitignore:9`），且 settings 内含机器绝对路径，新 clone 里 `-s .mvn-settings.xml` 直接指向不存在的文件。
- 根 `README.md:61/113/115` 与 `.github/workflows/ci.yml:36` 给出的构建/测试命令**都不带 `-s` 开关**——即公开入口沿用的正是被作废的那一类口径；`grep -rn mvn scripts/ --include=*.sh` 实测 0 命中，仓内没有任何脚本承载修正后的口径。
- 提交 `8e6eb0d` 的提交信息记录：六份 Dockerfile 因 build 阶段只 COPY 部分模块而父 pom 声明全部模块，`Child module ... does not exist` → **此前一次都没构建成功过**；修复后仅 leaderboard 一份实跑 build+run，其余 5 份同批修正未逐个重跑。`ci.yml` 全文无任何 docker 步骤（实测）。`docker-compose.services.yml:22-183` 已为 6 个服务定义 `context: .` + 各自 `dockerfile:`，即门槛可直接复用 compose 的构建定义；`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` 在 HEAD 实测退出码 0。
- 唯一的真库端到端测试 `leaderboard-service/.../LeaderboardDailySummaryMapperMysqlIT.java` 自述"surefire 默认不收集，只有 `-Dtest=` 显式指定才跑"，且需 `TASK108_IT_URL/USER/PASSWORD`；全仓 pom **无任何 surefire/failsafe 声明**（`grep -rn "failsafe\|surefire\|includes" --include=pom.xml .` 实测 0 命中），README「测试」节也只讲 Mockito 单测 → 这条检查没有任何被指路的路径。
- `web/package.json` 有 `type-check` 与 `build`（且 `web/pnpm-lock.yaml` 已 tracked），但 `ci.yml` 无 node 步骤、`web/README.md` 只写 `pnpm install` / `pnpm dev`（实测），而 `web/src/api/client.ts` 是窗口内热点（4 commits / churn 279，实测 `--numstat`）；提交 `3030f6f` 曾漏交 pnpm 锁文件后补交。注意 `web/package.json` **没有 `packageManager` 也没有 `engines` 字段** → runner 上 pnpm 不会凭空可用，需显式提供版本。
- `ci.yml:41` 的公开口径自检把 pathspec 限定在 `'*.md'`；实测非 markdown 载体有 **4 处命中**（3 个 `*-service/src/main/java` 的 javadoc + `verify-service/src/main/resources/application.yml:119` 的注释）→ 这道门槛对它声明的不变量在非 markdown 载体上完全不生效。两个必须在扩围时排除的自身命中：`ci.yml:38/41/42`（正则自身）与 `.gitignore:63`（该文件不匹配任何拟用扩展名 glob，天然不在范围内）。markdown 侧在 HEAD 实测 0 命中，扩围不涉及存量清理。
- `git rev-list --count origin/main..main` = **16**，`gh run list` 最近一次成功停在 2026-09-17 → 当前修订从未到达唯一的外部门槛，502 个 Episode 中 `withReviewedRelevantCheck = 0`。

**当前状态**：验收命令散在 README / CI / 台账散文里，三份口径互不一致且其中一份已被证明会假绿；交付面（镜像、compose、前端）在门槛上没有任何判据；结论是否到达外部门槛无人记录。

**期望状态**：仓内有且仅有一个 Maven 验收入口，它会**先把生效的依赖来源打印出来再跑**，来源与期望模式不符就非 0 退出（D12 类假绿从此需要主动绕过脚本才能发生）；CI 与 README 都指向它；compose / 镜像 / 前端各有一条最小判别式挂在门槛上；真库 IT 有确定的触发路径且"跳过"不会被当成"通过"；每条验收记录绑定 commit 与其门槛来源。

## What Changes

- 新增 `scripts/verify/mvn-verify.sh`：唯一 Maven 验收入口。`--mode=auto|offline|online`，脚本内部固定以 `clean` 开头（今天 CI 跑的就是 `mvn -B clean verify`，入口若省掉 clean 会复用上一轮 `target/` 产物，那是另一张假绿面孔）；offline 模式强制 `-o -s .mvn-settings.xml` 并**校验 settings 声明的 localRepository 目录真实存在**（纯文本解析，不依赖 maven-help-plugin）；online 模式剥离 `-s` 与 `-o`；探测不到期望来源时以专属退出码失败，与"测试失败"区分。透传阶段（`test|verify|package`）与 `-pl <模块> -am`，并提供 `--it` 定向跑真库端到端测试。
- `ci.yml` 的构建步骤改为调用该脚本的 online 模式；新增三步：compose 多文件 `config -q`（HEAD 实测已 0 退出，不会一上就红）、复用 compose 定义构建 1 份代表服务镜像（`docker compose ... build <service>`，不手写 `docker build -f`）、web 侧显式提供 pnpm 版本后执行 `pnpm install --frozen-lockfile` + `type-check` + `build`。
- 词面自检 pathspec 由 `'*.md'` 扩到 tracked 的 java / yml / json / sh / ts / sql / patch 载体，排除 `spec/changes/archive/**`、`docs/internal/**` 与 `ci.yml` 自身；**先清理已实测命中的 4 处（3 处 javadoc + 1 处 application.yml 注释）再扩围**，只改注释文本。
- 真库 IT 的触发条件、环境变量与"缺 env 即跳过、跳过不计通过"的判据写入 README「测试」节与 `scripts/verify/` 说明；`web/README.md` 增补验证节。
- 根 `README.md`「测试」节去掉写死的测试计数（实测 `@Test` 注解 284 处、`src/test` 下 tracked 文件 42 个含 1 个 `*IT`，文档仍写 15 类 125 个），改为以入口脚本汇总为准；`.env` 段落给出入库样例 `scripts/verify/env.example` 并删除与相邻段落矛盾的"已按此口径配置"表述。
- `work/mailbox` 收口清单增加一条必备步骤：每条验收记录绑定 commit id 与门槛来源（CI run 编号，或一次脚本 online 模式实跑结果）；未推送时显式标注"未达外部门槛"。**推送属外部写操作，仍需单独授权**，清单允许以 online 模式本地实跑作为替代凭据。

**明确不做**：

- 不引入 Flyway / Liquibase / Testcontainers，不新增 failsafe 或任何 Maven 插件；IT 继续用 `-Dtest=` 定向。
- 不把 `.m2-repo`（数百 MB）或含机器绝对路径的 `.mvn-settings.xml` 入库，不改 `.gitignore` 中这两条既有"不入库"决定；口径统一靠脚本与 CI 的 online 权威，不靠搬运本机状态。
- 不改任何业务 Java 逻辑（词面清理只动注释），不改 `sql/0*.sql` 与 `sql/migrations/**`。
- 不动 `spec/changes/archive/**` 与 `docs/internal/**` 的历史措辞。
- 本提案**不含**台账虚假声明（"已新增 CI 校验"与仓库实况不符）与派发契约可校验化这两项——它们属于"记录可信度"这一独立关注点，另开提案；本提案只把 `TASK-104/handoff.md` 中那两处并不存在的 CI 步骤作为现状证据引用。
- 不自动推送、不开新业务模块、不做覆盖率阈值门槛（JaCoCo 只报告是根 pom 明示的既有取舍）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「统一验收入口」「依赖来源可判定」「交付面最小判别式」「真库端到端测试有确定路径」「前端改动有门槛判据」「公开口径自检覆盖全部发布载体」「验收结论与修订绑定」；MODIFIED「多模块工程结构」。

### 受影响的代码
- 新增 `scripts/verify/mvn-verify.sh`、`scripts/verify/env.example`（及其说明）
- 修改 `.github/workflows/ci.yml`（构建步骤改调脚本；新增 compose config / 单镜像 build / web 检查；词面自检扩围）
- 修改 `README.md`（测试节口径、`.env` 段、快速开始的验收命令）
- 修改 `web/README.md`（验证节）、`web/package.json`（仅在需要补 lockfile 一致性时）
- 修改 4 处 tracked 源码/配置注释措辞（3 处 `*-service/src/main/java` javadoc + `verify-service/src/main/resources/application.yml` 一处注释，均为文本）
- 修改 `work/mailbox/PLAN.md`（收口清单新增门槛绑定步骤）

### 用户影响
- 开发者验收改为一行：`bash scripts/verify/mvn-verify.sh verify`；无离线仓的机器上自动走 online 并打印来源，不再需要记住 `-s/-o` 组合。
- 已有 `.env` 的机器无感；新 clone 从 `scripts/verify/env.example` 复制。

### API 变更
- 无对外接口变更；不涉及运行时行为，纯构建/门槛/文档面。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（README、web/README、scripts/verify 说明、收口清单）

## 时间线评估

中：约 1 ~ 1.5 天。其中"6 份 Dockerfile 逐个本地实跑构建"是纯时长项，可与其余步骤并行。

## 风险

- **词面自检扩围会让 CI 立刻红**（4 处非 markdown 载体命中 + `ci.yml` 自身含正则）→ 缓解：任务顺序上先清理后扩围；扩围前用同一条 `git grep` 以新 pathspec 本地预跑得 0 命中；`ci.yml` 自身纳入排除列表。另需知悉：`.gitignore:63` 的注释仍含同类措辞，因不匹配任何拟用扩展名 glob 而不在范围内——扩围后"全仓零命中"不成立，只有"范围内零命中"成立。
- **offline 与 online 两模式依赖集不同，可能给出不同结果**（D12 的 lettuce / xxl-job 就是这一类）→ 缓解：脚本先打印生效来源再跑；CI 固定 online 为权威口径；两模式结论不一致时以 online 为准，并在台账记差异与缺失构件。
- **`-o` 离线模式在构件缺失时报错被误读为"测试失败"** → 缓解：来源探测失败使用独立退出码与明确提示（区分"依赖来源不可判定/离线仓缺件"与"用例红"）。
- **单镜像 build 上门槛拖长 CI**（maven 层打包分钟级）→ 缓解：CI 只构 1 份代表镜像，其余 5 份由 compose `config -q` 与同批模式覆盖，本地逐个实跑结果记入台账。
- **web 挂 CI 后 node/pnpm 版本与锁文件成为新失败源** → 缓解：`--frozen-lockfile` 并把 lockfile 缺失定性为门槛失败（正是 `3030f6f` 想避免的那类事后补交）。
- **收口清单要求推送，与"推送需单独授权"冲突** → 缓解：清单显式允许以 online 模式本地实跑作为替代凭据，推送与否由人决定。
- **IT 长期没人跑，退化为死代码** → 缓解：README 与脚本给出确定入口 + "跳过不得计入通过"的判据，使未覆盖状态可见。

## 备注

- 公开 markdown 不写口径禁用词本身，也不写其他本机仓库的绝对路径；沿用 `ci.yml` 既有自检 step 做验证。
- 脚本只做"口径唯一化 + 来源打印"，不吞掉 mvn 原始输出与退出码，失败即停。
- `.m2-repo` 与 `logs/` 体量大且已忽略，本提案不改变这一点。
