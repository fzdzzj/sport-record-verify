# TASK-252 任务书（spec.md）

> **课题**：E 系批2——CI runner 迁移预备（E4 runner/action 迁移 + E31 trivyignore 漂移治理）  
> **级别**：MEDIUM 档（纯 CI 配置与文档注释，零核心逻辑零安全语义 → 免独立复核 + 指导亲核，CI 外部绿即收口；触碰 `.github/workflows/ci.yml` 基建 → 离线门禁仍全量逐位）  
> **责任服务**：`CI workflow`、`.trivyignore`  
> **开工基线**：TASK-251 完整收口后的 `main` HEAD（实际哈希开工时 `git rev-parse HEAD` 取得并回填；工作树干净；**开工前提：TASK-251 已封盘含 CI 绿**）  
> **状态**：待实施（串行第二批，TASK-251 收口前禁开工）  

---

## 1. 背景与目标

E 系侦察 E4（★时间窗硬约束）：`ubuntu-latest` 2026-10-19 起灰度迁移 Ubuntu 26（窗口至 11-19），Node 20 已从 runner 退役且逃生开关已下线；本仓 5 个 action 全为 node20 代 @v4（checkout/setup-java/cache/setup-node/upload-artifact）被强制跑 Node 24；workflow 无 workflow_dispatch（迁移前无法 dry-run）、无 concurrency、无 timeout-minutes；Docker 链三处（compose config / 6 镜像构建 / Trivy）为迁移红风险面。本机 CI run `38849720261` annotations 亲见双预警，与侦察证据互证。

E31：ci.yml 注释称「历史存量 13 项 CRITICAL 已登记」而 `.trivyignore` 实际仅 2 条 CVE，口径漂移且无过期机制。

**目标**：钉扎 runner 止血；补手动试跑通道供 10-19 后验证 Ubuntu 26；actions 升 Node 24 代；补并发取消与超时上限；校准 trivyignore 口径并补复核期限。

## 2. 严格改动文件白名单（恰 4 项，禁超出）

1. `.github/workflows/ci.yml`（R1-R5 全部动作）
2. `.trivyignore`（R5 复核期限注释）
3. `work/mailbox/tasks/TASK-252/spec.md`（C-02 回填占位符）
4. `work/mailbox/tasks/TASK-252/handoff.md`（C-02 回填五节）

## 3. 判别式

### R1 钉扎 runner（止血）
- build / web 两 job `runs-on: ubuntu-latest` → `ubuntu-24.04`；注释注明「2026-10-19 ubuntu-latest→Ubuntu 26 迁移窗口止血钉扎；26 经 probe 验证通过后另课题切换」。

### R2 actions 升 Node 24 代
- 五处升级：`checkout@v4→v5`、`setup-java@v4→v5`、`cache@v4→v5`、`setup-node@v4→v5`、`upload-artifact@v4→v6`；
- 升级前逐一核对各 action 新 major changelog 的破坏性变更（如 upload-artifact v6 行为差异），有则评估并在 handoff 登记；若某目标 major 当时尚未稳定发布 → 该项保持现状 + handoff 登记原因 + 停止汇报裁决。

### R3 workflow_dispatch + Ubuntu 26 试跑通道
- 顶层加 `workflow_dispatch`（inputs: `probe26` boolean default false）；
- 新增 job `probe-ubuntu26`：`runs-on: ubuntu-26.04`，条件 `if: github.event_name == 'workflow_dispatch' && inputs.probe26 == true`；步骤对齐 build job 的 Docker 链三处（占位 env → compose config → 6 镜像构建 → Trivy）；JDK/checkout 步骤同款；
- 注释注明「ubuntu-26.04 标签 2026-10-19 可用前不得触发 probe26=true（无 runner 必红）」；日常 push 零影响。

### R4 并发取消与超时上限
- 顶层 `concurrency`：`group: ${{ github.workflow }}-${{ github.ref }}`，`cancel-in-progress: true`（并发 push 取消旧 run，省 6 镜像重复构建）；
- `timeout-minutes`：build 45 / web 15 / probe-ubuntu26 45（按本机实测 build 12m12s / web 29s 三倍裕量定）。

### R5 trivyignore 漂移治理（E31）
- ci.yml「历史存量 13 项已登记」注释校准为实际条数（2 条）并注明口径订正来源；
- `.trivyignore` 既有 2 条 CVE 各补一行「复核期限」注释（格式如：`# 复核期限 2027-04：届时重扫，确认上游修复即删除本条`）；不引入 Trivy exp 新格式（避免门禁语义变更）。

## 4. 门禁（基建触碰 → 全量逐位，全部 rc=0 并留证）

1. **后端全模块**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test`——全模块逐位，测试数与基线持平只增不减（本课题零 Java 改动，预期 52/62/202/300/182/104/29 逐位不变）；
2. **compose 校验**：`docker compose -f docker-compose.yml -f docker-compose.services.yml config` rc=0；
3. **前端回归**：`cd web; pnpm run type-check; pnpm test` 全绿（零前端改动照跑，白名单外零 diff 论证）；
4. **契约门**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-252 --baseline=<开工时 git rev-parse HEAD>` rc=0；
5. **token 门**：29 项 `git grep -cF` tracked 全量口径只增不减（清单勿枚举字面量原文）；
6. **词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机显式 `-E` + 探针阳性对照）；
7. **`git diff --check`**：提交前净。

## 5. 双提交规范

- **C-01 实施笔**：`chore(ci): 钉扎runner与升级actions及补dispatch试跑与并发超时和trivyignore治理（TASK-252）`——仅文件 1、2；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-252实施完成与门禁回填（TASK-252）`——仅文件 3、4；回填 handoff 五节并把开工基线占位符替换为实际哈希；
- 已知受限（handoff §4 须登记）：① ubuntu-26.04 真实试跑未做（标签 10-19 可用前不可触发；收口后由用户手动 `workflow_dispatch probe26=true` 验证，读数随补记笔登记）；② workflow 语义与 action 兼容性的权威验证为推送后 CI 实跑（本地无 actionlint，不引入新工具；CI 红则按签名归因，禁重试刷绿）；③ actions 新 major 的破坏性变更以 changelog 核对结论为准，逐项登记。
