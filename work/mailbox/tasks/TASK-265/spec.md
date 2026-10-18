# TASK-265 立项任务书（全链路高并发压测与容量基准建立）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导侧终裁；开工基线 main @ eeb8400；FULL 档，C-01 b61806e + C-02 7d108b5，指导亲核三块 PASS + 独立复核五块全 PASS 零阻断，全部门禁通过，封盘批次统一推送 origin/main，待 CI 外部终验）

## 1. 证据链（指导侧现场亲验实测，2026-10-18）

1. **中间件与微服务基础设施状态坐实**：
   - 宿主与容器环境：`sport-verify-mysql:3307` 正常运行且 healthy；`sport-verify-postgis:5433` 正常运行且 healthy；`sport-verify-nacos:8848` healthy；`sport-verify-rocketmq-namesrv:9876` healthy；`sport-verify-rocketmq-broker:10909` healthy；宿主机 `redis:6379` 活跃响应 PONG；
   - 依赖与网络环境全部打通，各微服务端口（8080 网关、8081 user、8082 record、8083 verify、8084 leaderboard、8085 mapmatch）就绪。
2. **既有压测工具资产与覆盖缺口**：
   - 资产：`scripts/perf/LoadTest.java` 具备基于虚拟线程的高并发压测调度内核，输出完整的 QPS、P50/P95/P99 延迟与状态码统计；
   - 缺口：原脚本仅支持单场景 `POST /record/api/records`，缺少对全仓历经治理后的四大核心业务链路的针对性压测场景（Feed 流读取 `GET /record/api/records/feed`、点赞批量查询 `POST /record/api/records/likes/batch`、排行榜查询 `GET /leaderboard/api/leaderboard/overall`、记录写入全链路）；缺少统一的一键驱动与多场景基准自动化脚本。
3. **容量基准与连接池验证目标**：
   - 在全仓 54 项治理闭环（E 系 31 + G 系 21 + 新候选 2）的干净稳态下，建立覆盖写链路、读链路、聚合链路的高并发容量基准；
   - 实测 100/300/500 三档并发下的系统表现，获取真实瓶颈与 HikariCP 默认连接池（10）的水位表现，形成完整的工程容量基准报告。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 压测工具/脚本/报告（3 文件）**：
  1. `scripts/perf/LoadTest.java`（R1 多场景与请求头支持增强）
  2. `scripts/perf/benchmark-e2e.sh`（R2 端到端一键压测编排与自动化基准脚本，100755 权限）
  3. `docs/perf/benchmark-e2e-capacity-report.md`（R3 压测实测数据与容量分析报告）
- **C-02 台账（1 文件）**：
  4. `work/mailbox/tasks/TASK-265/handoff.md`（实施回填）

零触碰面声明：除上述 3+1 文件外，全仓微服务生产 Java 业务代码、常规测试用例、pom.xml、package.json、前端 web/、数据库 DDL 100% 零触碰；严禁未经压测实测对照盲调连接池或业务配置。

## 3. 修法要求

- **R1（LoadTest.java 多场景与协议增强）**：
  - 扩展 `LoadTest.java`：
    - 支持自定义 HTTP 请求头（如 `--header "Authorization: Bearer ..."` 或 `--header "X-User-Id: 1"`）；
    - 支持指定 HTTP 方法（GET / POST / PUT）；
    - 支持针对非 2xx 响应的分类统计（200/400/401/403/404/429/500）；
    - 保持纯 JVM 单文件零外部依赖运行（`java scripts/perf/LoadTest.java`）。
- **R2（benchmark-e2e.sh 自动化压测编排脚本）**：
  - 新增 `scripts/perf/benchmark-e2e.sh`（必须具备 100755 执行权限，纯 LF 行尾）；
  - 支持一键或分步执行核心链路压测场景：
    - ① 记录提交写链路（`POST /record/api/records`，写事务与异步 verify 链路）；
    - ② 动态流读链路（`GET /record/api/records/feed`，涉及 G3 assertCanView 优化与 G13 分页）；
    - ③ 批量点赞查询链路（`POST /record/api/records/likes/batch`，涉及 G3 批量 DB 与 Feign 懒拉）；
    - ④ 排行榜查询链路（`GET /leaderboard/api/leaderboard/overall`，涉及 D1/G12 L1+L2 缓存与抖动）；
  - 输出格式化的 CSV / JSON 数据至 `docs/perf/data/raw/`。
- **R3（全链路端到端容量基准报告）**：
  - 输出规范详细的 `docs/perf/benchmark-e2e-capacity-report.md`；
  - 汇总 100/300/500 并发下的 QPS、P50、P95、P99 延迟、错误率与限流状态；
  - 对照记录 MySQL 线程与连接池表现，给出定量分析与生产容量建议。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`，全量七模块 surefire 基线 **63/78/221/321/203/110/36** 只增不减，零失败零跳过；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-265 --baseline=c4ad1b1`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2162 + 29 = 2191**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 修改文件保持既有行尾（LF/CRLF），新脚本与文档纯 LF 结尾；
- 双提交 C-01（工具/脚本/报告）/ C-02（台账）；`git status --porcelain` 收口为空；
- 独立复核子 agent 审查把关。

## 5. 受限声明（预登记）

1. 压测运行依赖宿主机已有 Java 运行环境与本地 Docker 容器，若部分服务在压测期间内存压力过高，应优先保证单服务或核心服务稳定采样，避免盲目超卖系统内存；
2. 压测脚本和报告为工程评估与基准留痕，生产业务代码保持零修改。

## 6. 停止条件与升级路径

- 若生产业务代码、pom.xml、package.json 或 SQL DDL 出现变动，立即停手；
- 若 surefire 离线门禁有任何模块失败或计数减少，立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- 七门禁全绿，surefire 保持 63/78/221/321/203/110/36；
- 自动化压测脚本成功运行并产出真实采样数据；
- `docs/perf/benchmark-e2e-capacity-report.md` 完整落库；
- 独立复核五块全 PASS 零阻断；
- 指导亲核三块全 PASS。
