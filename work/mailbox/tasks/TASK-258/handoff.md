# TASK-258 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-17 实施完成；FULL 档双提交 + 七门禁全绿 + 真机 PostGIS 5 用例实跑全绿）

## 1. 开工基线

- 开工基线：main @ `4f3060f`（TASK-258 立项笔；`git rev-parse HEAD~1` 实测一致）
- C-01 业务/测试/脚本提交：`97ddde9`（`fix(mapmatch): 增补PostGIS真实空间库集成测试与SQL契约测试（TASK-258）`，恰 3 新文件 563 行；脚本经 `git update-index --chmod=+x` 携带 100755 执行位后 amend 定稿）
- 任务书：`work/mailbox/tasks/TASK-258/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 4f3060f` 双向全等）

- 改动清单（恰 4 项 = C-01 业务/测试/脚本 3 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchSqlContractTest.java
  - mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchPostgisIT.java
  - scripts/mapmatch/verify-postgis.sh
  - work/mailbox/tasks/TASK-258/handoff.md
- 零触碰面声明：除上述 4 文件外，全仓业务代码、pom.xml、package.json、web 前端、docker-compose、.env 等 100% 零触碰；生产 Java 业务代码（MapMatchService）零触碰（`git diff 4f3060f -- mapmatch-service/src/main` 改动数 = 0 实测）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（08:49 min）；surefire **63/78/221/321/203/110/36**（common/gateway/user/record/verify/leaderboard/mapmatch）——mapmatch 35→36 净增 1 契约测（MapMatchSqlContractTest），其余六模块严格持平，零失败零跳过 |
| 2 | 真机 PostGIS 实测 | `bash scripts/mapmatch/verify-postgis.sh --it` | 0 | 四步核验 rc=0：容器 running/healthy（127.0.0.1:5433）、road_edge 计数 **7780** 与数据集契约一致、`idx_road_edge_geom => ... USING gist (geom)` 在位、EXPLAIN 实测 `Bitmap Index Scan on idx_road_edge_geom`（Index Cond: geom && st_expand(...)，无 Seq Scan）；定向 IT `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`（1.410 s）——R1 ①生产 CANDIDATE_SQL（反射读取）真库执行 + WKT 形态/切片范围/首顶点回代垂距 <1m、②EXPLAIN 命中 GIST、③人民广场周边最长真实边 12 点等距沿路轨迹、④远海悬浮轨迹 offRoadRatio==1.0 且 300m 封顶、⑤全库最长边（5965.7 m）40 点轨迹 D6 递归拆块（叶子查询 ≥2）与禁拆单块聚合指标逐位一致，全部实测通过 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-258 --baseline=4f3060f` | 0 | 判据 A 两件套齐；判据 B 4 路径双向全等（清单多报 0、改动集未声明 0）——C-02 落库后终态复跑实测 |
| 4 | token 门 29 项 | `git grep -cF` 逐项（全仓 tracked 累加口径） | 0 | C-01 树实测 SUM = **2133**（C-01 三新文件逐文件 0 命中，逐项分布与 TASK-264 终树逐位一致）；C-02 树实测 SUM = **2162** = 派发书期望绝对口径（2133 + 29）；29 项逐位持平零减少（因本 handoff 自枚举效应各 +1）：13.4=159、18.0=175、73.93=116、68.8=111、6315=91、1.8612=90、3.3066=90、5.7056=96、9.408=81、36525962432=74、36586847965=71、36438897772=73、36399582548=71、36098038547=69、2806=111、598=108、36736221648=68、36808102571=56、36821040708=50、36845152965=47、36871294588=45、36880083885=47、36958994260=46、36976873215=41、36992632143=38、36995450125=36、37008317295=35、37021305016=34、37591580687=33 |
| 5 | 词面门 | `git grep -n -I -iE -f <模式文件>`（CI 8 禁词权威口径，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；模式文件 od 实测 LF 无 \r；阳性探针 probe258.txt 置仓库根（**.trae/ 整目录被 .gitignore L65 剪枝，探针不可置于 .trae/tmp**）`--untracked` 四 locale 各 rc=0 恰命中探针行 1 次后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | checkstyle delta=0 | `mvn-verify.sh --mode=offline --static=mapmatch-service` | 1（存量形态） | 存量 **146** 违规全部落在 4 个既有 main 源（MapMatchService.java / MapMatchController.java / MatchProperties.java / MapMatchApplication.java，sun_checks 80 列/FinalParameters/JavadocMethod 等存量项）；本笔 3 新文件全在 test/scripts、不在 --static main 源扫描范围；`git diff 4f3060f -- mapmatch-service/src/main` 改动数 = 0（main 源逐字节一致）→ delta=0 |
| 7 | diff 净与行尾 | `git diff 4f3060f HEAD --check` / `git ls-files --eol` | 0 | diff --check 净（rc=0）；3 新文件 `i/lf w/lf` 纯 LF（od 末字节 0x0A 无 0x0D 复核） |

## 4. 条件项与非阻断登记（如实回填）

- **R1 环境漂移发现与多口令自适应探测（本笔环境侧关键实测发现）**：
  - 首轮 IT 实跑呈现 `Tests run: 0` + BUILD SUCCESS 假绿——`@BeforeAll` 探测未连通触发 `Assumptions` 类级跳过（surefire 不计 Skipped），读数必须以 Tests run 计数为准，不得以 rc=0 为凭；
  - 排查链：容器内 `psql` loopback trust 掩蔽宿主侧认证差异 → 独立 JDBC 探针（postgresql-42.7.3 + JDK21 单文件源码启动）坐实 **.env 声明的 POSTGRES_PASSWORD 在宿主侧 scram 认证失败，历史卷实际口令为镜像默认 `postgres`**（POSTGRES_PASSWORD 仅首次初始化数据卷时生效，与 2026-10-17 指导侧亲验记录不矛盾——指导侧经容器内 exec 连接）；
  - 处置：IT 口令解析改为候选序依次尝试（`POSTGIS_PASSWORD` 显式值 → `.env` 声明值 → 镜像默认 `postgres`），与多端口探测（显式 `POSTGIS_PORT` → 5433 → 5432）同构；**.env 与容器状态零变更**（.env 不在白名单，容器口令未做任何 ALTER），多候选探测即覆盖漂移；
  - 五用例复跑 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` 真绿落袋。
- **R3 脚本可执行位**：任务书 R3 明文「带执行权限」，`verify-postgis.sh` 以 100755 入库；既有 `import-road-network.sh` 在 git 内为 100644 的历史差异如实登记，本笔遵循任务书字面口径。
- **受约声明沿 spec §5 三项**：
  1. `MapMatchPostgisIT` 依赖外部 Docker PostGIS 容器（sport-verify-postgis），CI 或缺容器环境经 `assumeTrue` 优雅跳过（类级跳过不进 surefire 计数，不影响 36 只增不减），离线构建与 CI 权威保障由 `MapMatchSqlContractTest` 承载；
  2. 真实路网边 7780 条仅覆盖上海核心切片（实测 ST_Extent BOX(121.4167893 31.1896148, 121.5520316 31.2592878)），测试样例轨迹全部选用切片内坐标，悬浮用例取远海无路网区作反例，不外推全量全球路网；
  3. 生产代码 `MapMatchService` 零改动，本课题为端到端复验与测试基础设施补全（IT 经反射读取 CANDIDATE_SQL，防复制粘贴漂移）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 下临时件（msg258c1.txt / tokens258.txt / lex258.txt / Probe258.java / tmp258_*.log）均在 gitignore 范围内，不污染工作树。
- 未推送：`origin/main..HEAD` 收口后 = **4 笔**（TASK-264 外部终验补记 `96caca4` + 立项笔 `4f3060f` + C-01 `97ddde9` + C-02）——推送由指导侧批次统一执行，本笔严格零推送。
- handoff 状态行「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 4 路径双向全等）。
