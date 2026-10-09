# TASK-190 harden-compose-secrets 任务书（中间件编排口令 .env 化与全端口回环绑定）

## 0. 红线（违任一条即 FAILED 停手回报）

1. **compose 插值形态已裁决**：口令类一律 `${VAR:?required: cp scripts/verify/env.example .env}` 必填（未设或空值即解析期失败）；**禁止** `:-root` / `:-postgres` / `:-admin` 弱默认回退、禁止 `$$` 转义错位（会把主机侧插值变成容器内字面量）；端口类变量保持 `${VAR:-数字}` 默认；GRAFANA_ADMIN_USER 例外保持 `:-admin`
2. 零触碰清单：ci.yml / scripts/verify/mvn-verify.sh / scripts/verify/mailbox-contract.sh / docker-compose.services.yml / docker-compose.perf.yml / gateway-service / web / typed-router.d.ts / sql/ / 父 pom / record-service（含 sharding.yaml——已参数化无需再动）/ 仓库根 .env（使用方本地文件已按目标态预置，禁改禁抄值入任何 tracked 产物）
3. Redis 不加 requirepass、Nacos 不开鉴权、RocketMQ 不加 ACL（裁决：留后续课题；本课题防御主机制 = 十端口回环绑定）
4. 零 Java main 源码改动：只改 docker-compose.yml / env.example / 四服务 application.yml / README + 新增测试文件
5. 口令值不入 tracked 文件与台账 / handoff（env.example 占位样例除外）；compose 插值留证一律脱敏（「与 .env 同值」或长度表述，禁明文）；env.example 新占位值由执行侧新生成，**禁复用本机 .env 实值**
6. 性能与安全收益数字零 claim（禁「风险降低 X%」类表述，只登记机制性事实）
7. 词面门正则字面量不入任何 tracked 文档与输出（TASK-184 F1 教训）；受保护 token 字面量不枚举进新文档（TASK-188 N1 教训）
8. 不执行破坏性操作：禁 `docker compose down -v` / 卷删除 / 库内改口令（存量卷迁移属使用方操作，只在文档登记指引）
9. 停止条件：offline 基线 550（36/41/117/137/147/62/10）回退或 811 增 / compose 三判别式任一不达预期 / 需触碰任一零触碰面 / 占位符单测无法纯 JVM 确定性落地

## 1. 背景与侦察实证（指导侧已亲核，2026-10-09）

- **docker-compose.yml 现状**：L43 `MYSQL_ROOT_PASSWORD: root` 字面量、L56 healthcheck `-proot`、L136 `POSTGRES_PASSWORD: postgres`、L181-182 Grafana admin/admin、L23 `NACOS_AUTH_ENABLE=false`（本课题不动）；十处端口全接口发布（8848/9848/3306/6379/9876/10911/10909/5432/9090/3000）
- **.env 惰性实证**：env.example 已定义全部口令变量但 docker-compose.yml 零 `${}` 引用——.env 口令值对中间件栈当前完全无效（F02 成立的机制根源）
- **服务侧实证**：user application.yml L23 / verify L46 / leaderboard L53 字面量 root；mapmatch L36 字面量 postgres；record sharding.yaml L34 已占位 `${MYSQL_PASSWORD:root}`（全仓唯一先例）；docker-compose.services.yml L59 注释声称的占位符当前不实、本课题实施后为真（该文件零改动）；services compose `env_file: [.env]` 机制自动把 .env 变量传入服务容器——服务侧占位符补齐后容器内即自动生效
- **本地 .env 亲读**：已按目标态预置（强口令 SvLocal 模式 + Redis 不设口令注释 + 端口 3307/5433）——本课题使 tracked 侧对齐该口径；.env 实值禁入任何 tracked 产物
- **CI 机制核验**：ci.yml L42-48 `cp scripts/verify/env.example .env` 后 `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`——必填插值变量均已在 env.example，ci.yml 零触碰维持门绿（memory 教训「无 .env 时 compose config 失败」已由占位机制覆盖）
- **perf overlay 核验**：docker-compose.perf.yml 仅覆盖 mysql command（刷盘参数），无口令 / 端口重声明——零触碰安全
- **测试面核验**：仅 verify 两个 MysqlIT（scratch 库口径）以自注入 Map 引用 spring.datasource.* 键，不读 application.yml 口令——占位符改动零冲突；checkstyle 不扫 test 源码（TASK-188/189 实证）→ 811 持平预期
- **行尾基线**（git ls-files --eol 实测）：docker-compose.yml / README.md / user application.yml 工作区 CRLF；mapmatch application.yml / env.example 工作区 LF；leaderboard / verify application.yml 工作区混合——修改文件保持各自既有行尾（仅动目标行，不重排其它行）
- **附带核验**：grafana/provisioning（datasources 仅 URL 无凭据）、rocketmq/broker.conf（无凭据）、sql/migrations/README.md L19（默认账号可环境变量覆盖，既有机制）均无需改动
- **基线**：开工 HEAD=bc2388e（TASK-189 补记笔待批推送，沿先例随本课题推送），origin/main...main=0 1，工作区干净；offline 550（36/41/117/137/147/62/10）；静态 811；受保护 token 29 项开工实测为准（TASK-189 收口真值 2030 参照）

## 2. 预注册实施设计

### 2.1 docker-compose.yml（三处口令面 + healthcheck + 十端口 + 头部）

- 头部说明段（L1-11 的「说明」区）追加警示：仅限本地开发——口令必填走 .env（`cp scripts/verify/env.example .env`），端口仅绑定 127.0.0.1 回环，生产部署须整体重审（密钥管理 / 鉴权 / 内网）
- mysql：

```yaml
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?required: cp scripts/verify/env.example .env}
    ports:
      - "127.0.0.1:${MYSQL_PORT:-3306}:3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-p${MYSQL_ROOT_PASSWORD}"]
```

（TZ / command / volumes / 既有注释零改动；healthcheck 仅末参数 `-proot` → `-p${MYSQL_ROOT_PASSWORD}`）

- postgis：`POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?required: cp scripts/verify/env.example .env}`；ports `- "127.0.0.1:${POSTGRES_PORT:-5432}:5432"`（POSTGRES_DB / healthcheck / volumes 零改动）
- grafana：

```yaml
    environment:
      # 账号口令经 .env 注入（本地样例见 scripts/verify/env.example）；生产改走密钥管理/SSO
      - GF_SECURITY_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:?required: cp scripts/verify/env.example .env}
    ports:
      - "127.0.0.1:3000:3000"   # 控制台（账号口令见 .env，登录后打开预置面板「全局监控总览」）
```

（原「本地演示固定账号密码」注释同步改写；volumes / depends_on 零改动）

- 其余端口前缀（服务定义其余部分零改动）：nacos `- "127.0.0.1:8848:8848"` / `- "127.0.0.1:9848:9848"`；redis `- "127.0.0.1:6379:6379"`；rocketmq-namesrv `- "127.0.0.1:9876:9876"`；rocketmq-broker `- "127.0.0.1:10911:10911"` / `- "127.0.0.1:10909:10909"`；prometheus `- "127.0.0.1:9090:9090"`
- nacos 的 `NACOS_AUTH_ENABLE=false` 与「关闭鉴权，本地开发直连」注释保持原样（裁决留后续课题）

### 2.2 scripts/verify/env.example（占位值升级，变量名零增删）

- `MYSQL_ROOT_PASSWORD=SvLocal-MySQL-<8位hex>-ChangeMe`；`MYSQL_PASSWORD` 同值（保留「必须与 MYSQL_ROOT_PASSWORD 一致」注释）；`POSTGRES_PASSWORD=SvLocal-PG-<8位hex>-ChangeMe`；`GRAFANA_ADMIN_USER=admin`（不变）；`GRAFANA_ADMIN_PASSWORD=SvLocal-Grafana-<8位hex>-ChangeMe`
- `<8位hex>` 由执行侧新生成、各值互不相同，禁复用本机 .env 实值
- 注释段：头部「首次初始化生效 / down -v 或改库口令」既有说明保留；口令占位区补一行回环绑定口径（compose 端口仅绑 127.0.0.1，跨机访问走网关）与 Redis 不设口令说明（本地回环口径，生产必须启用认证）
- MYSQL_PORT / POSTGRES_PORT / TASK108_IT_* / TASK110_IT_* 段零改动

### 2.3 四服务 application.yml（占位符补齐，沿 record sharding.yaml 先例）

- user / verify / leaderboard：`password: root` → `password: ${MYSQL_ROOT_PASSWORD:root}`，行上补注释（数据源口令经环境变量注入，与 compose .env 变量同名；默认 root 仅限本地未导出环境时）
- mapmatch：`password: postgres` → `password: ${POSTGRES_PASSWORD:postgres}`，注释同要旨
- username / url / hikari 段与其余内容零改动；行尾沿各自文件既有形态（leaderboard / verify 工作区混合行尾——仅动目标行）

### 2.4 绑定判别式单测（四服务同构各 1 类 2 例，纯 JVM 确定性不启上下文）

测试类 `DataSourcePasswordEnvBindingTest`（包：user=`com.sportverify.user.config`、verify=`com.sportverify.verify.config`、leaderboard=`com.sportverify.leaderboard.config`、mapmatch=`com.sportverify.mapmatch.config`——无 config 测试包则新立）：

| 用例 | 断言 |
| --- | --- |
| credentialInjectedFromEnvironment | 真实 application.yml 经 `YamlPropertySourceLoader`（多 document 逐个）装入 `MutablePropertySources` + `MapPropertySource`(口令变量=`it-injected-credential`) → `new Binder(ConfigurationPropertySources.get(ps), new PropertySourcesPlaceholdersResolver(ps))` 绑定 `DataSourceProperties`（`bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get()`）→ `getPassword()` 等于注入值——守护「占位符回归字面量」（字面量不解析环境变量即红） |
| localFallbackDefaultWithoutInjection | 仅装 yaml 源（**不装 StandardEnvironment 系统源**，隔离宿主机 shell 同名变量）同构造绑定 → `getPassword()` 等于 `root`（mapmatch 为 `postgres`）——守护回退默认语义 |

构造要点（预注册防坑）：Binder 必须经 `PropertySourcesPlaceholdersResolver` 构造（占位符 `${VAR:default}` 才会被解析）——直接 `new Binder(ConfigurationPropertySources.get(ps))` 不解析占位符、按字面量绑定出**假绿**；中文 Javadoc 说明断言目的，风格沿各服务既有测试。

### 2.5 README.md（快速开始两处微改，其余零改动）

- 前置条件行之后、`docker compose up -d` 之前插入第 0 步：`cp scripts/verify/env.example .env`（提示：改掉占位口令；口令仅数据卷首次初始化生效，存量卷换口令见 env.example 头部说明）
- 启动六服务步骤（第 4 步）注释块补一行：启动前导出口令变量（Git Bash：`set -a; . ./.env; set +a`），数据源口令与 compose 一致

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/harden-compose-secrets/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-190/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-190 compose 口令治理提案与任务书`
- C-01（11 文件）：`docker-compose.yml`、`scripts/verify/env.example`、`README.md`、`user-service/src/main/resources/application.yml`、`verify-service/src/main/resources/application.yml`、`leaderboard-service/src/main/resources/application.yml`、`mapmatch-service/src/main/resources/application.yml`、`user-service/src/test/java/com/sportverify/user/config/DataSourcePasswordEnvBindingTest.java`（新）、`verify-service/src/test/java/com/sportverify/verify/config/DataSourcePasswordEnvBindingTest.java`（新）、`leaderboard-service/src/test/java/com/sportverify/leaderboard/config/DataSourcePasswordEnvBindingTest.java`（新）、`mapmatch-service/src/test/java/com/sportverify/mapmatch/config/DataSourcePasswordEnvBindingTest.java`（新），主题：`fix(compose): 中间件弱口令 .env 化与全端口回环绑定（TASK-190）`
- C-02：tasks.json 全勾 + 本 spec §7 纯追加 + `work/mailbox/tasks/TASK-190/handoff.md` + `work/mailbox/PLAN.md` 纯追加，主题：`docs(mailbox): 登记 TASK-190 compose 口令治理验收与台账闭环（TASK-190）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-189 收口真值 2030 参照）；收口读数以**收口态实测**为准。只增不减；新文档**不枚举 token 字面量**（沿 TASK-188 N1 教训）。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法（`python -m json.tool`）rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 + 三态（正则字面量不入任何 tracked 文档与输出）；`git diff --check` rc=0；契约门在途 `--open TASK-190 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整（.java 新文件 LF；修改文件保持既有行尾——docker-compose.yml / README / user 工作区 CRLF、mapmatch / env.example LF、leaderboard / verify 混合，仅动目标行）
2. **compose 三判别式（C-01 后亲跑留证，本课题核心新增门）**：
   - 正向（仓库根，有 .env，shell 未导出任何口令变量）：`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` rc=0（CI 同款命令）
   - 插值读数：`docker compose -f docker-compose.yml config` 输出——十处端口映射逐一确认 `127.0.0.1` 前缀（计数留证）；口令键已插值（值脱敏：登记「与 .env 同值」或长度表述，禁明文）
   - 负向（必填判别式）：`.trae/tmp/` 复制 docker-compose.yml（该目录无 .env）后 `docker compose -f docker-compose.yml config -q` → rc≠0 且报错信息含 MYSQL_ROOT_PASSWORD——证明口令无弱默认兜底；临时文件用毕即删
3. **收口门禁（C-02 后亲跑留证）**：`bash scripts/verify/mvn-verify.sh --mode=offline test` 全量新基线逐位登记（550 只增不减，+8 预计落 user / verify / leaderboard / mapmatch 各 +2，以实测为准）；`bash scripts/verify/mvn-verify.sh --static=record-service` 811 不增；零 main Java 核验（`git diff --name-only <派发笔>..HEAD` 仅白名单 11 文件，无任何 main Java）；typed-router 零漂移（`git diff --exit-code -- web/src/typed-router.d.ts` rc=0）；契约门无参 rc=0；PLAN.md 自派发笔起纯追加
4. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（compose 三判别式读数、四服务单测读数、脱敏口径说明、env.example 新占位值模式说明）/ 逐门实测表 / token 前后读数 / 未覆盖项（UNDETERMINED：运行时全栈联调——存量卷迁移属使用方操作不属门禁，沿 182/185/186/187/188/189 口径）/ 提交表（显式哈希，禁时效指针）
5. **运行时联调（可选不判失败）**：使用方迁移存量卷（down -v 重建或库内改口令）后 `docker compose up -d` 全 healthy + 导出 .env 后服务连通；本机未迁移则 UNDETERMINED

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`a77e0bd02b854ab47066983fe64a5cc6548d717e`（`a77e0bd`） `docs(spec): 派发 TASK-190 compose 口令治理提案与任务书`
- C-01 实施笔：`ad038179553546333265d85704310c74a7b6f217`（`ad03817`） `fix(compose): 中间件弱口令 .env 化与全端口回环绑定（TASK-190）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）` `docs(mailbox): 登记 TASK-190 compose 口令治理验收与台账闭环（TASK-190）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测回填）

- user-service：`DataSourcePasswordEnvBindingTest` Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
  - `credentialInjectedFromEnvironment`：pass（真实 application.yml 经占位符解析绑定，注入 MapPropertySource 后 getPassword() 等于注入值）
  - `localFallbackDefaultWithoutInjection`：pass（仅装 yaml 源、不含系统源，getPassword() 等于 root）
- verify-service：同上（同构 2 例全 pass，回退默认 root）
- leaderboard-service：同上（同构 2 例全 pass，回退默认 root）
- mapmatch-service：同上（同构 2 例全 pass，回退默认 postgres）

### 7.3 门禁读数（收口态实测回填）

- compose 三判别式：①正向 `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` rc=0；②插值读数 `docker compose -f docker-compose.yml config` 十处端口映射（3000/3307/8848/9848/5433/9090/6379/10911/10909/9876）逐一带 `host_ip: 127.0.0.1`，三个口令键已插值（值脱敏）；③负向 `.trae/tmp/` 无 .env 副本 `config -q` rc=1，报错含必填变量名（compose 报告次序不定，观测到 POSTGRES_PASSWORD 与 MYSQL_ROOT_PASSWORD，均证明无弱默认兜底）
- offline 全量逐位：`36/41/119/137/149/64/12` = **558**（基线 550 只增不减，+8 全落 user 117→119 / verify 147→149 / leaderboard 62→64 / mapmatch 10→12），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态门：`--static=record-service` Checkstyle **811 持平**未增，rc=1 为基线违规模块预期
- 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-190 --baseline=a77e0bd` rc=0（判据 A 两件套齐 + 判据 B 清单一致）
- 词面门四形态：改动文件集与 repo 全量（CI 权威 exclude 口径）四形态（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1，探针三态 HIT rc=0，PROBE_GONE=yes
- token 29 项：开工实测 SUM=2030；C-01 后实测 SUM=2030；收口态实测 SUM=2030 只增不减（新文档不枚举 token 字面量，沿 TASK-188 N1 教训）
- 只改清单全等核验：C-01 恰白名单 11 文件，零 main Java 代码；C-02 恰白名单 4 文件；typed-router.d.ts 零漂移（`git diff --exit-code -- web/src/typed-router.d.ts` rc=0）
- 行尾与末尾换行核验：修改文件保持既有行尾（docker-compose.yml / README / user 工作区 CRLF、mapmatch / env.example LF、leaderboard / verify 混合，仅动目标行）；四个新测试文件纯 LF + 末尾换行完整
