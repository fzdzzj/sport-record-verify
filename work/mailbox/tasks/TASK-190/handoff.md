# TASK-190 harden-compose-secrets 中间件编排口令 .env 化与全端口回环绑定 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `a77e0bd02b854ab47066983fe64a5cc6548d717e`（`a77e0bd`，派发笔），父为 `bc2388e`（TASK-189 补记笔）；开工前 `git status --porcelain` 为空。派发笔另含 `spec/changes/harden-compose-secrets/proposal.md` 与 `specs/sport-record-verify/spec-delta.md`，二者相对派发笔基线零改动，不属本任务 diff 集（派发笔的 `tasks.json` 与 `TASK-190/spec.md` 因 C-02 回填而在 diff 集内）。
- **未推送口径**：`git rev-list --left-right --count origin/main...main` 开工 `0 2`（派发笔与父均未推送，沿先例禁止推送，不建 PR）。
- **受保护 token**：29 项开工实测 SUM=2030（repo 全量 `git grep -cF` 口径；TASK-189 收口真值 2030 为参照）。
- **红线逐条核验**（§0）：
  1. compose 插值形态：三处口令一律 `${VAR:?required: cp scripts/verify/env.example .env}` 必填（解析期无值时即失败）；无 `:-root` / `:-postgres` 弱默认回退；无 `$$` 转义错位；端口类变量保持 `${VAR:-数字}`；`GRAFANA_ADMIN_USER` 例外保持 `:-admin`；healthcheck 末参数 `-proot` → `-p${MYSQL_ROOT_PASSWORD}`。
  2. 零触碰清单遵守：`ci.yml`、`scripts/verify/mvn-verify.sh`、`scripts/verify/mailbox-contract.sh`、`docker-compose.services.yml`、`docker-compose.perf.yml`、`gateway-service/`、`web/`（含 `web/src/typed-router.d.ts`）、`sql/`、父 pom、`record-service/`（含 `sharding.yaml`）、仓库根 `.env` 全程零触碰。
  3. Redis 未加 requirepass、Nacos 未开鉴权、RocketMQ 未加 ACL；`NACOS_AUTH_ENABLE=false` 及其注释保持原样。
  4. 零 Java main 源码改动：仅 compose / env.example / 四服务 application.yml / README + 四个新测试类。
  5. 口令零明文入 tracked 产物（env.example 占位样例除外）；台账与 handoff 插值留证一律脱敏。
  6. 性能与安全收益数字零 claim，只登记机制性事实。
  7. 词面门正则字面量与受保护 token 字面量不入任何新文档（沿 TASK-184 F1 与 TASK-188 N1 教训）。
  8. 未执行破坏性操作：无 `docker compose down -v`、无卷删除、无库内改口令。
  9. 停止条件核验：未触发——offline 550 只增不减（实测 558）、811 持平未增、compose 三判别式达预期、未触碰任一零触碰面、占位符单测以纯 JVM 确定性落地。

## 2. 一句话结论与三支裁决

**docker-compose.yml 三处中间件口令改 `:?` 必填插值（healthcheck 同源 `${MYSQL_ROOT_PASSWORD}`）+ 十处端口加 `127.0.0.1` 回环前缀 + 头部仅限本地警示；user/verify/leaderboard/mapmatch 四服务数据源口令补占位符（沿 record sharding.yaml 先例）；env.example 占位值升级为互不相同的 SvLocal 模式；README 快速开始补第 0 步 cp .env 引导；四服务各新增绑定判别式单测 2 例（全仓 550→558，+8，全绿），静态 811 持平，零 main Java 改动，compose 三判别式全达预期，全门禁通过，判定 PASSED**；契约门在途/无参均 rc=0。外部终验待推送后下一次外部门槛（第 32 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | compose 三口令必填插值 + healthcheck 同源 + 十端口回环 + 四服务占位符 + env.example/README 引导 + 四服务判别式单测全绿（全仓 558，+8）+ 零 main Java 代码 + 静态 811 持平 + compose 三判别式全达预期 + 契约门 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 32 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only a77e0bd..HEAD` 逐条比对）

C-01 实施笔（恰 11 文件）：

- `docker-compose.yml`
- `README.md`
- `scripts/verify/env.example`
- `user-service/src/main/resources/application.yml`
- `verify-service/src/main/resources/application.yml`
- `leaderboard-service/src/main/resources/application.yml`
- `mapmatch-service/src/main/resources/application.yml`
- `user-service/src/test/java/com/sportverify/user/config/DataSourcePasswordEnvBindingTest.java`
- `verify-service/src/test/java/com/sportverify/verify/config/DataSourcePasswordEnvBindingTest.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/DataSourcePasswordEnvBindingTest.java`
- `mapmatch-service/src/test/java/com/sportverify/mapmatch/config/DataSourcePasswordEnvBindingTest.java`

C-02 台账笔（恰 4 文件）：

- `spec/changes/harden-compose-secrets/tasks.json`
- `work/mailbox/tasks/TASK-190/spec.md`
- `work/mailbox/tasks/TASK-190/handoff.md`
- `work/mailbox/PLAN.md`

零触碰清单：`ci.yml`、`docker-compose*.yml`（services / perf overlay）、`scripts/verify/mvn-verify.sh`、`scripts/verify/mailbox-contract.sh`、`gateway-service/`、`web/`、`web/src/typed-router.d.ts`、`sql/`、父 pom、`record-service/`（含 `sharding.yaml`）、仓库根 `.env`。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希。处置：§8 列出派发笔 `a77e0bd`、C-01 `ad03817` 显式哈希；C-02 自指为台账收口笔，其显式哈希以本回传报告给出（沿 TASK-182/185/186/187/188/189 台账终态化先例的固有自指）。 | 台账提交表终态化固有的单一自指；两笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **必填插值 YAML 加引号**：任务书 §2.1 预注册形态 `${MYSQL_ROOT_PASSWORD:?required: cp scripts/verify/env.example .env}` 消息体含 `: `（冒号加空格），在未加引号的 YAML plain scalar 中非法（实测 go-yaml 解析报 mapping values are not allowed in this context）。最小修正=给三处口令标量加双引号，插值表达式逐字保留，未引入弱默认、未使用 `$$` 转义。 | 必要且最小偏差；compose 判别式①由红转绿即为证。 |
| D3 | **负向判别式报错变量名不唯一**：任务书 §6.2 负向判别式预期报错含 MYSQL_ROOT_PASSWORD；compose 仅报首个缺失的必填变量且次序不定（并行插值），实测两次分别观测到 POSTGRES_PASSWORD 与 MYSQL_ROOT_PASSWORD。 | 机制结论（口令无弱默认兜底，缺值即解析期失败）不变；不属门禁回归。 |
| D4 | **词面门 repo 全量口径**：本仓历史存档 `.github/workflows/ci.yml`（正则本体）与 `spec/changes/archive/**`（历史措辞）两处命中，均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集）。 | CI 权威口径处置；本任务改动文件集独立 ZERO_HIT 已实测。本行不落任何词面门正则字面量（TASK-184 F1 教训）。 |

## 5. 实施证据（含验收要点判据）

### 5.1 docker-compose.yml（三处口令插值 + healthcheck 同源 + 十端口回环 + 头部警示）

- 头部说明段追加仅限本地警示：口令必填走 .env（`cp scripts/verify/env.example .env`），十处端口仅绑 `127.0.0.1` 回环，生产部署须整体重审（密钥管理 / 鉴权 / 内网）。
- mysql：`MYSQL_ROOT_PASSWORD: "${MYSQL_ROOT_PASSWORD:?required: cp scripts/verify/env.example .env}"`（加引号）；`healthcheck.test` 末参数 `-p${MYSQL_ROOT_PASSWORD}`；端口 `- "127.0.0.1:${MYSQL_PORT:-3306}:3306"`。
- postgis：`POSTGRES_PASSWORD: "${POSTGRES_PASSWORD:?required: cp scripts/verify/env.example .env}"`（加引号）；端口 `- "127.0.0.1:${POSTGRES_PORT:-5432}:5432"`。
- grafana：`- GF_SECURITY_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}`（不加引号，保持默认）；`- "GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:?required: cp scripts/verify/env.example .env}"`（加引号）；端口 `- "127.0.0.1:3000:3000"`。
- 其余端口前缀（服务定义其余部分零改动）：nacos `127.0.0.1:8848` / `127.0.0.1:9848`；redis `127.0.0.1:6379`；rocketmq-namesrv `127.0.0.1:9876`；rocketmq-broker `127.0.0.1:10911` / `127.0.0.1:10909`；prometheus `127.0.0.1:9090`（合共十处）。
- `NACOS_AUTH_ENABLE=false` 及其注释、镜像 / 卷 / 网络 / 既有注释段零改动。

### 5.2 env.example 占位值模式（脱敏）

- 占位值升级为 `SvLocal-<组件>-<8位hex>-ChangeMe` 模式，三者互不相同、且与本机 `.env` 现行实值不同（未复用）：`MYSQL_ROOT_PASSWORD` 与 `MYSQL_PASSWORD` 同值并保留一致性注释；`POSTGRES_PASSWORD` 与 `GRAFANA_ADMIN_PASSWORD` 各独立；`GRAFANA_ADMIN_USER=admin` 不变。具体 hex 见 tracked 文件 env.example 本体，本报告不复写。
- 注释段补回环绑定口径（compose 端口仅绑 `127.0.0.1`，跨机访问走网关 8080）与 Redis 不设口令说明（本地回环口径，生产须启用认证）。
- 变量名零增删；MYSQL_PORT / POSTGRES_PORT / TASK108_IT_* / TASK110_IT_* 段零改动。

### 5.3 四服务 application.yml（沿 record sharding.yaml 先例）

- user / verify / leaderboard：`password: root` → `password: ${MYSQL_ROOT_PASSWORD:root}` + 注释行（数据源口令经环境变量注入，与 compose .env 变量同名；默认 root 仅限本地未导出环境时）。
- mapmatch：`password: postgres` → `password: ${POSTGRES_PASSWORD:postgres}` + 同要旨注释。
- username / url / hikari 段与其余内容零改动；行尾沿各自文件既有形态（仅动目标行）。

### 5.4 四服务绑定判别式单测读数（纯 JVM，不启上下文）

- 四服务各 `DataSourcePasswordEnvBindingTest` Tests run: 2, Failures: 0, Errors: 0, Skipped: 0：
  - user / verify / leaderboard：`credentialInjectedFromEnvironment` pass（注入 `MapPropertySource` 后 `getPassword()` 等于注入值）+ `localFallbackDefaultWithoutInjection` pass（仅装 yaml 源、不含系统源，回退 `root`）。
  - mapmatch：两例同构，回退默认 `postgres`。
- 构造要点：经 `ConfigurationPropertySources.from(ps)` + `PropertySourcesPlaceholdersResolver(ps)` 构造 Binder，占位符 `${VAR:default}` 才被解析；回退用例的 `MutablePropertySources` 不含 `StandardEnvironment` 系统源，隔离宿主机 shell 同名变量。

### 5.5 compose 三判别式读数（C-01 后亲跑）

| 判别式 | 命令 | 读数 | rc |
| --- | --- | --- | --- |
| ① 正向 | `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`（仓库根，.env 在位，shell 未导出口令变量） | 无输出 | 0 |
| ② 插值读数 | `docker compose -f docker-compose.yml config` | 十处端口 `host_ip` 全为 `127.0.0.1`（3000 / 3307 / 8848 / 9848 / 5433 / 9090 / 6379 / 10911 / 10909 / 9876）；`MYSQL_ROOT_PASSWORD` / `POSTGRES_PASSWORD` / `GF_SECURITY_ADMIN_PASSWORD` 三键已插值（值脱敏：与本机 .env 同值，长度表述略）；`healthcheck.test` 末参数 `-p<mysql_root_password>`（与 .env 同值） | 0 |
| ③ 负向 | `.trae/tmp/` 复制 docker-compose.yml（该目录无 .env）后 `docker compose -f docker-compose.yml config -q` | 报错 `required variable <VAR> is missing a value: required: cp scripts/verify/env.example .env`，两次运行分别观测 POSTGRES_PASSWORD 与 MYSQL_ROOT_PASSWORD（证明无弱默认兜底）；临时文件用毕即删 | 1（预期非零） |

### 5.6 零 main Java 核验

- `git diff --name-only a77e0bd..ad03817`（C-01 范围）恰 11 文件、无任何 `src/main/java`；`git diff --stat` C-01 为 +366/-24，其中 4 个新测试类为纯新增。C-02 仅 4 个台账文件（tasks.json / spec.md / handoff.md / PLAN.md）。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集） | 四形态全 ZERO_HIT | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径） | 四形态全 ZERO_HIT（见 §4 D4） | 1（预期非零） |
| 词面门探针三态 | state1 ZERO_HIT rc=1 / state2 探针 HIT rc=0 / state3 移除后 rc=1，PROBE_GONE=yes | 三态符合 |
| `git diff --check`（C-01 / C-02 提交前） | 干净，无空白错误 | 0 |
| compose 判别式 ① / ② / ③ | 见 §5.5 | 0 / 0 / 1（负向预期） |
| 契约门在途 `--open TASK-190 --baseline=a77e0bd` | 判据 A 两件套齐 + 1 待办放行 + 判据 B 清单一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐 + 判据 B 清单一致 | 0 |
| offline 全量 `--mode=offline test` | `36/41/119/137/149/64/12` = **558**（基线 550→558，+8），Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增） | 1（基线违规模块预期） |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| token 29 项 | 开工 SUM=2030；C-01 后 SUM=2030；收口态 SUM=2030，只增不减 | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

- **开工实测（SUM=2030）**：全量实测 29 项和为 **2030**（与 TASK-189 收口真值一致，参照）。沿 TASK-188 N1 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准。
- **C-01 后实测（SUM=2030）**：compose / env.example / 四服务配置与四个测试类均未引入受保护 token 字面量，29 项读数逐位与开工持平，SUM=2030。
- **C-02 收口复测（SUM=2030，只增不减）**：台账 / handoff / PLAN 纯追加不引入任何受保护 token 字面量，收口态实测仍为 **SUM=2030**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `a77e0bd02b854ab47066983fe64a5cc6548d717e`（`a77e0bd`） | `docs(spec): 派发 TASK-190 compose 口令治理提案与任务书` |
| C-01 实施 | `ad038179553546333265d85704310c74a7b6f217`（`ad03817`） | `fix(compose): 中间件弱口令 .env 化与全端口回环绑定（TASK-190）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-190 compose 口令治理验收与台账闭环（TASK-190）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **运行时全栈联调 UNDETERMINED**：本机未执行存量卷迁移与 `docker compose up -d` 全 healthy 验证（中间件全栈未起）。沿 TASK-182/185/186/187/188/189 口径登记 UNDETERMINED 不判失败；compose 配置可解析性、插值与回环绑定已由三判别式完全覆盖。
2. **存量卷迁移属使用方操作说明**（非门禁项）：口令类变量仅在数据卷首次初始化生效，换口令需 `docker compose down -v` 重建卷或库内改口令，已在 env.example / README 注释登记指引；本任务未执行任何破坏性操作。
3. **性能与安全收益数字零 claim**（纪律遵守）：本变更只登记机制性事实（必填插值、回环绑定、占位符注入），不写任何量化收益。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 32 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。
