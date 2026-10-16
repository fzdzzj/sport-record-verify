# TASK-252 台账（handoff）

> **TASK**：E 系批2——CI runner 迁移预备（E4 + E31，MEDIUM 档，免独立复核）  
> **开工基线**：`b4bdef8`（TASK-251 补记笔；2026-10-16 开工时 `git rev-parse HEAD` 取得；工作树干净）
> **方案**：2026-10-16 E 系只读立项侦察产出，用户拍板批2 与批1 串行执行、批1先行收口后本批开工  
> **状态**：已封盘（MEDIUM 档免独立复核，指导亲核三块 PASS，终裁 **ACCEPT 予以封盘**，2026-10-16；CI 外部终验读数由补记笔落库；受限：ubuntu-26.04 真实试跑待 2026-10-19 标签可用后手动 dispatch probe26=true 验证）

## 1. 实施摘要

R1-R5 逐项落地（与 spec §3 判别式一一对应）：

- **R1 钉扎 runner**（spec §3 R1）：`build`/`web` 两 job `runs-on: ubuntu-latest` → `ubuntu-24.04`（ci.yml L24-26 / L135-137），各加行内注释「2026-10-19 ubuntu-latest→Ubuntu 26 迁移窗口止血钉扎；26 经 probe 通道验证通过后另课题平移」。
- **R2 actions 升 Node 24 代**（spec §3 R2）：5 action 6 uses 升级全部落地，逐项 changelog 核对结论见 §4 表；全部目标 major 已稳定发布，无「保持现状」项。升级点：`checkout@v4→v5`（build L32 + web L143）、`setup-java@v4→v5`（build L35）、`cache@v4→v5`（build L42）、`upload-artifact@v4→v6`（build L123）、`setup-node@v4→v5`（web L146）；新增 probe job 内 checkout/setup-java 均直接用 v5（L187/L191）。
- **R3 workflow_dispatch + Ubuntu 26 试跑通道**（spec §3 R3）：顶层 `workflow_dispatch`（inputs.probe26 boolean default false，ci.yml L10-15）+ 新 job `probe-ubuntu26`（L178-220）：runs-on ubuntu-26.04、`if: github.event_name == 'workflow_dispatch' && inputs.probe26 == true`、timeout 45；步骤 = Checkout → JDK 21（同 build 款）→ Provision 占位 env（同 build 两行）→ Compose parse check → Build 6 镜像 → Trivy 门禁跑次；注释注明「ubuntu-26.04 标签 2026-10-19 起可用、此前禁触发 probe26=true（无 runner 必红）」。
- **R4 concurrency / timeout**（spec §3 R4）：顶层 `concurrency`（group `${{ github.workflow }}-${{ github.ref }}`、cancel-in-progress: true，L17-20）；`timeout-minutes` build 45 / web 15 / probe-ubuntu26 45。
- **R5 trivyignore 漂移治理**（spec §3 R5 / E31）：ci.yml Trivy 注释「历史存量 13 项」校准为「2 项（CVE-2026-47892 / CVE-2026-47884）」并注明订正来源 E31/TASK-252（L80-81）；.trivyignore 两条 CVE 各补一行复核期限注释（`# 复核期限 2027-04：届时重扫，确认上游修复即删除本条`，纯注释，未引入 Trivy exp 新格式）。

## 2. 只改清单全等声明

- `git diff --name-only b4bdef8`（C-01+C-02 收口态）恰 **4 条**，与 spec §2 白名单 4 项**双向全等**：
  1. `.github/workflows/ci.yml`
  2. `./.trivyignore`
  3. `work/mailbox/tasks/TASK-252/spec.md`
  4. `work/mailbox/tasks/TASK-252/handoff.md`
- 未跟踪文件：词面门探针临时件已用后删（.trae 契约排除，收口态零残留）；本课题改动集 4 条路径全 ASCII（无 TASK-251 的 CJK 提取缺口问题，契约门判据 B 不受影响）。
- **时点边界**：C-01 实施笔 = 文件 1、2；C-02 台账笔 = 文件 3、4；两笔并集恰 4 条，零白名单外 diff。

## 3. 门禁读数

1. **后端全模块离线门禁**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0**，BUILD SUCCESS（9/9 模块，12:16 min）。七模块 surefire 逐位：common **52** / gateway-service **71** / user-service **205** / record-service **300** / verify-service **182** / leaderboard-service **104** / mapmatch-service **29**，全部 Failures=0 / Errors=0 / Skipped=0——与 TASK-251 handoff §3 收口实测**逐位持平**（spec §4.1 标注 52/62/202 系 TASK-251 开工前基线旧值，见 §4 偏差说明）。
2. **compose 校验**：`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` → **rc=0**（本地 .env 已含 INTERNAL_API_TOKEN）。
3. **前端回归**：`pnpm --dir web run type-check` → rc=0；`pnpm --dir web run test` → rc=0，4 测试文件 **61 测全绿**（pace 6 / token 6 / gpx 11 / client 38）。零前端改动，白名单外零 diff。
4. **契约门**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-252 --baseline=b4bdef8` → **rc=0**（收口态，C-02 提交前实测）。
5. **token 门**（29 项受保护 token，`git grep -cF` tracked 全量口径；清单权威来源 = work/mailbox/archive/PLAN-history-001-210.md 受保护 token 节，台账不枚举字面量）：基线（b4bdef8，C-01 前实测）**SUM=2015** → 收口态（C-02 编辑完成后提交前）**SUM=2015**，29 项逐项只增不减、`TOKEN_VIOLATIONS=0`。时点边界：基线读数于改动前测得、终值于 C-02 文件编辑完成后提交前测得；归档旧读 2030 为 TASK-205 期数值（期间含 TASK-251 已登记的口径替换负增长），本课题以开工基线 b4bdef8 实测 2015 为相对基准。
6. **词面门**（CI 权威口径 `git grep -n -I -iE`，本机模式文件法绕开中文 127：模式放 `.trae/tmp/wording.pat` 用后删，显式 -E + 合成探针阳性对照）：
   - 合成探针阳性对照（模式有效性证明）：bash `grep -n -iE -f .trae/tmp/wording.pat .trae/tmp/probe.txt` → **rc=0 命中**；git `git grep -n -I -iE -f .trae/tmp/wording.pat --no-index -- .trae/tmp/probe.txt` → **rc=0 命中**；
   - CI-exact 零命中：`git grep -n -I -iE -f .trae/tmp/wording.pat -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'` → **rc=1（零命中）**。
7. **`git diff --check`**：C-01 提交前 **rc=0 净**；C-02 提交前复跑 **rc=0 净**。行尾形态：ci.yml/.trivyignore 保持基线 **LF**（`git ls-files --eol` = i/lf w/lf），两件套新增行纯 LF、无 BOM。附加：ci.yml 经 pyyaml 机器级解析通过（jobs=build/web/probe-ubuntu26、on 三键、probe26 default=false、concurrency 组、三处 timeout、8 处 uses 全为升级后版本零 v4 残留）。

## 4. 受限与偏差

**受限（spec §5 预注册三项）**：
1. **ubuntu-26.04 真实试跑未做**：标签 2026-10-19 起可用，此前禁触发 `probe26=true`（无 runner 必红）；收口后由用户手动 `workflow_dispatch probe26=true` 验证，读数随补记笔登记；
2. **workflow 语义与 action 兼容性的权威验证为推送后 CI 实跑**：本地无 actionlint（不引入新工具）；CI 红则按签名归因、禁重试刷绿；
3. **actions 新 major 破坏性变更以 changelog 核对结论为准**（下表，逐项登记）。

**R2 逐 action changelog 核对结论表**（核对日期 2026-10-16，来源 = 各 action 官方 Releases 页 / checkout CHANGELOG.md）：

| action | 旧→新 | 目标 major 稳定发布 | 破坏性变更要点 | 本仓用法影响 | 处置 |
| --- | --- | --- | --- | --- | --- |
| checkout | v4→v5 | ✅ v5.0.0 (2025-08-11)；最新 v5.1.0 | Node24 运行时、自托管 runner≥2.327.1；v5.1.0 起 `pull_request_target`/`workflow_run` 下默认禁检出 fork PR 代码（需 allow-unsafe-pr-checkout 放行） | 本仓触发仅 push/pull_request/workflow_dispatch，无 fork 检出面，零影响 | 照升 v5 |
| setup-java | v4→v5 | ✅ v5.0.0 (2025-08-21)；最新 v5.7.0 | Node24 + runner≥2.327.1；v5.7.0 弃用 legacy Adopt 发行版（移除在 v6） | temurin/21 输入不变；CI annotation 明示 v4 停更 | 照升 v5 |
| cache | v4→v5 | ✅ v5.0.0 (2025-12-11)；最新 v5.1.0 | Node24 + runner≥2.327.1；save 行为微调（v5.0.2 不再重试 429、v5.1.0 兼容只读缓存） | key/restore-keys 与 ~/.m2 用法语义不变 | 照升 v5 |
| upload-artifact | v4→v6 | ✅ v6.0.0 (2025-12-12) | v4→v6 合计：Node24 默认运行时 + @actions/artifact 包升级（artifact 服务后端自 v4 起已切换） | if-no-files-found: warn 与 jacoco glob 不变 | 照升 v6（注：v7.0.0 (2026-02-26) 已发布，spec 定 v6，不追 v7） |
| setup-node | v4→v5 | ✅ v5.0.0 (2025-09-04) | Node24 + runner≥2.327.1；新增自动缓存（package.json 含 packageManager 字段时启用） | 本仓 web/package.json 无 packageManager/engines 字段（实测确认），无意外缓存 | 照升 v5 |

**偏差与说明**：
- spec §4.1 surefire 预期 `52/62/202/300/182/104/29` 为 TASK-251 **开工前**基线旧值（TASK-251 handoff §3 实测开工基线即该值）；TASK-251 收口实测 `52/71/205/300/182/104/29`（gateway +9、user +3），本课题零 Java 改动，以收口实测值为持平预期——与派发包标注一致；
- probe-ubuntu26 步骤按 spec §3 R3「JDK/checkout 步骤同款」纳入 setup-java 步骤（派发包步骤清单未列 JDK），兼作 setup-java@v5 在 ubuntu-26.04 的兼容性验证；
- probe job 的 Trivy 仅跑**门禁跑次**（CRITICAL --exit-code 1，与 build 门禁语义一致；输出跑次从简，probe 目的为通道可达性验证）；
- 归档 PLAN-history 旧读「29 项 token SUM=2030」为 TASK-205 期读数，本课题以开工基线 b4bdef8 实测 2015 为基准（相对 b4bdef8 只增不减，TOKEN_VIOLATIONS=0）。

## 5. 双提交记录

- **C-01 实施笔**：`66d0ec3`（`66d0ec32d4c9b26585c8788999ee816c8d4377bf`）：
  `chore(ci): 钉扎runner与升级actions及补dispatch试跑与并发超时和trivyignore治理（TASK-252）` —— 仅文件 1、2（`.github/workflows/ci.yml`、`.trivyignore`）；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-252实施完成与门禁回填（TASK-252）` —— 仅文件 3、4（`spec.md` 回填开工基线占位符为 `b4bdef8` 并改状态「已实施」、`handoff.md` 五节回填）。C-02 为收口最终 HEAD；本笔内容引用本笔哈希属自指，台账不落具体值（`git log --oneline -1` / `git rev-parse HEAD` 即为该笔）；
- **收口日净声明**：`git status --porcelain` 为空（`.trae/tmp` 临时件已删，零残留）。
