# TASK-200 fix-d3-prod-boot-defects 四服务生产启动缺陷修复（round-3 改派轮）—— 回传笔（handoff / 执行侧）

## 1. 一句话结论与三支裁决

round-3 普查式根治收口：缺陷D（NotificationController 同构双构造器）经构造器级 `@Autowired` 标注（恰 +1 行）修复，user 生产 jar 实起越过 notificationController 创建点并 `Started UserApplication`，liveness 200 + `{"status":"UP"}`；同构缺陷类全仓清零。四服务生产实起全绿达成 D3 使命。门禁：offline 609 逐位全绿、static 双模块 rc=0、六探针 CI 等效全绿、契约门双态、token 29 项 SUM 只增不减、词面门全绿。C-01 恰 11 文件（round-1 九文件 + NotificationService + NotificationController），C-02 恰 4 台账文件。

**三支裁决**：
1. **改派合规**：v3 由指导侧裁定改派（round-2 §8），扩权白名单 10→11 文件 + NotificationController.java，普查式根治同构缺陷类；round-1/round-2 十文件改动集原样沿用（红线 §0.3 不回退）。defectD 修复与 v3 §2.3 预注册设计逐字一致（恰 +1 行构造器标注，import 既有零新增）。
2. **唯一新增改动边界**：本轮唯一代码改动 = NotificationController 构造器级 @Autowired；`@RequiredArgsConstructor`、字段级 `@Autowired(required=false)`、方法体、javadoc 业务语义零改动，未删显式构造器（NotificationControllerTest:55 唯一 2 参手工 new 调用方零破坏）。
3. **停止条件未触发**：user 实起越过 notificationController 后未暴露第五个新失败签名（四服务全 UP）；offline 609 回退、静态净增、越界触碰均未发生。

## 2. 开工规程核验

| 项 | 实测 |
| --- | --- |
| 基线 | v3 改派笔 HEAD `e9d8898`（`e9d8898282bbe3e63abd3d26491c7f3b28df4d25`，父 `5c45511`）；工作区恰 10 文件 M（round-1 九文件 + round-2 NotificationService 恰 +1 行），与 v3 §8 停工态自洽 |
| token 开工 | 29 项 tracked `git grep -cF` 权威口径 SUM=2030（与 round-1/round-2 基线持平；清单 TASK-187 handoff §7） |
| 四件通读 | v3 任务书 spec.md + proposal.md + tasks.json 全读；§1.4 缺陷D 归因与普查实证、§2.3 修复边界、§3 停止条件、§4 白名单照执行；冲突以 v3 为准 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

C-01 实施笔（恰 11 文件）：
- pom.xml（父，fastjson2 v1 兼容包 property + dependencyManagement）
- user-service/pom.xml（fastjson runtime 依赖）
- record-service/pom.xml（fastjson runtime 依赖）
- verify-service/pom.xml（fastjson runtime 依赖）
- leaderboard-service/pom.xml（fastjson runtime 依赖）
- user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java（缺陷A：加 @Component）
- user-service/src/main/java/com/sportverify/user/service/NotificationService.java（缺陷C：构造器级 @Autowired，round-2 完成）
- user-service/src/main/java/com/sportverify/user/controller/NotificationController.java（缺陷D：构造器级 @Autowired，round-3 唯一新增）
- user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java（探针掩蔽清理：移除 @Import）
- verify-service/src/test/java/com/sportverify/verify/health/VerifyProbeTest.java（探针掩蔽清理：模板 mock 删除）
- record-service/src/test/java/com/sportverify/record/health/RecordProbeTest.java（探针掩蔽清理：模板 mock 删除）

C-02 台账笔（恰 4 文件）：
- spec/changes/fix-d3-prod-boot-defects/tasks.json（勾选翻转）
- work/mailbox/tasks/TASK-200/spec.md（§7 收口记录回填）
- work/mailbox/tasks/TASK-200/handoff.md（本文件新建）
- work/mailbox/PLAN.md（纯追加）

## 4. round-1/round-2 停止与改派记录（v3 §8 沿用留证 + round-3 延续）

- **round-1**（派发笔 `13e9ece`）：完成缺陷A/B 全部修复与门禁验证（补仓、offline 609 逐位、六探针 CI 等效六档全绿、静态双模块、三服务生产实起 liveness UP、user 越过 WS 装配期、token 开工 2030、恰 9 文件自洽）；user 冒烟暴露缺陷C（NotificationService 双构造器），触发 v1 停止条件 §3.1/§3.5 零提交停工。v2 改派笔 `5c45511`：扩权白名单 9→10 文件。
- **round-2**：完成缺陷C 修复（NotificationService.java 恰 +1 行构造器标注）；user 实起实证 notificationService bean 创建成功（失败点前移）；NotificationServiceTest/NotificationServicePushTest 零回归；token 开工复测 2030 持平；`--mode=online package` rc=0 四 jar 重建；恰 10 文件自洽零提交。user 实起随即暴露缺陷D（NotificationController 同构双构造器，`BeanCreationException: notificationController → No default constructor found`），触发 v2 停止条件 §3.1 零提交停手。
- **round-3（本轮）**：v3 普查式根治。修复缺陷D 后四服务生产实起全绿，同构缺陷类全仓清零；未触发任何停止条件。

## 5. 实施读数（C-01 diff 口径）

- **diff 口径**（`git diff --stat` 恰 11 文件 +78 −33）：round-1/round-2 十文件原样沿用（pom 五处 fastjson 回补、StompConnectAuthInterceptor @Component、UserProbeTest @Import 移除、Verify/Record 探针模板 mock 删除、NotificationService 构造器标注）；round-3 唯一新增 = NotificationController 显式 2 参构造前加 `@Autowired`（恰 +1 行，import 既有）。
- **约束满足**：未删显式构造器；`@RequiredArgsConstructor`、字段级注解、方法体、javadoc 业务语义零改动（`git diff` 仅 +1 行注释无其他改动）。
- **构造器解析链路**：Spring 唯一标注构造器被择定，2 参（NotificationService + NotificationPreferenceService）均可解析（前者 @Service 且 round-2 实证创建成功、后者 @Service v3 §1.4 亲核）；字段级 `@Autowired(required=false)` 构造后注入语义不变。

## 6. 生产 jar 实起冒烟（四服务全量，最终 11 文件态）

**方法**（round-1 配方沿用）：`mvn-verify.sh --mode=online package` 重建四 jar → `D:\develop1\jdk21\bin\java.exe -jar` 实起（server.port 错峰）→ `/actuator/health/liveness` 200 + UP。本机中间件在线实况（MySQL 3307 / Nacos 8848 / namesrv 9876+broker / Redis 6379）。共享中间件容器曾因 Docker Desktop idle 代理退出（Exited 255），round-3 起始时以 `docker start` 恢复并留证，全程未修改 docker-compose/停止共享容器。

| 服务 | jar | port | 结果 | Started | 关键留证 |
| --- | --- | --- | --- | --- | --- |
| user | sport-verify-user-service | 18091 | LIVENESS: HTTP 200 {"status":"UP"} | `Started UserApplication`（实起成功） | 越过 notificationController 创建点（无 `Error creating bean 'notificationController'`、无 NoSuchMethodException） |
| record | sport-verify-record-service | 18092 | LIVENESS: HTTP 200 {"status":"UP"} | `Started RecordApplication in 38.617 seconds` | round-1 缺陷B 反转保持 |
| verify | sport-verify-verify-service | 18093 | LIVENESS: HTTP 200 {"status":"UP"} | `Started VerifyApplication in 28.866 seconds` | round-1 缺陷B 反转保持 |
| leaderboard | sport-verify-leaderboard-service | 18094 | LIVENESS: HTTP 200 {"status":"UP"} | `Started LeaderboardApplication in 29.21 seconds` | round-1 缺陷B 反转保持 |

中间件容器恢复留证：`docker start sport-verify-mysql sport-verify-rocketmq-namesrv sport-verify-rocketmq-broker sport-verify-nacos` 后四容器 `Up (healthy)`，端口 3307/8848/9848/9876/10911/6379 全部可达。

## 7. 逐门实测表（收口全量门禁，基于最终 11 文件态）

| 门禁 | 时点 | 读数 | 结论 |
| --- | --- | --- | --- |
| user 实起 | C-01 前 | Started UserApplication，越过 notificationController 创建点，liveness 200 UP | 绿 |
| 四服务冒烟 | C-01 前 | §6 表四服务全 liveness 200 UP | 绿 |
| offline 全量 | C-01 前 | `--mode=offline test` rc=0，逐位 36/48/130/151/159/70/15 = 609（NotificationControllerTest 在 user 130 内零回归） | 绿 |
| static record-service | C-01 前 | `--static=record-service` rc=0 | 绿 |
| static leaderboard-service | C-01 前 | `--static=leaderboard-service` rc=0（首轮并行 .m2-repo 父 pom install 竞争 rc=1 定界为并发 artifact，串行复跑 clean rc=0） | 绿 |
| 六探针 CI 等效 | C-01 前 | offline 内实证六探针全绿：UserProbeTest 3/0/0/0、VerifyProbeTest 3/0/0/0、RecordProbeTest 3/0/0/0、GatewayProbeTest 4/0/0/0、LeaderboardProbeTest 3/0/0/0、MapMatchProbeTest 3/0/0/0 | 绿 |
| 契约门在途态 | C-01 后 | `--open TASK-200 --baseline=e9d8898` rc=0（判据 A + 判据 B 清单比对） | 绿 |
| 契约门无参态 | C-02 提交后复跑 | rc=0 | 绿 |
| token 收口 | C-01 后 | 29 项 tracked `git grep -cF` SUM=2030，只增不减（开工/收口双测） | 绿 |
| 词面门 | 收口 | CI word-check 正则四形态 ZERO_HIT（rc=1 = 无命中），新文档零触发形态 | 绿 |
| diff --check | C-01 前/后 | 干净 | 干净 |
| 行尾 | 收口 | 修改文件保持既有行尾（autocrlf=true，blob LF）；新文件 handoff.md blob 级 pure LF + 末尾换行 | 合规 |
| 只改清单 | 收口 | C-01 恰 11 文件、C-02 恰 4 文件，§3 清单与 `git diff --name-only` 全等 | 全等 |
| 零越界 | 收口 | 零触碰 ci.yml/mvn-verify.sh/mailbox-contract.sh/docker-compose*/gateway/mapmatch/web/sql/ADR/api/common/.trivyignore/仓库根 .env/record 测试夹具 sharding.yaml | 合规 |

## 8. 偏差登记

1. **D1 共享中间件容器代理退出与恢复**：round-3 起始时 `sport-verify-mysql/nacos/namesrv/broker` 四容器 Exited(255)（Docker Desktop idle 5 分钟致退，round-2 冒烟结束后空窗所致），Nacos gRPC 9848 随之拒连导致首轮 user 冒烟 http=000。处置：`docker start` 恢复四容器至 `Up (healthy)` 后复跑，未修改 docker-compose、未停共享中间件容器（红线 §0.1 零触碰）、未改动任何代码/配置。首轮 user 冒烟失败为环境空窗，非缺陷签名；复跑即全绿，不触发停止条件。
2. **D2 static 双模块并行 install 竞争 artifact**：`--static=record-service` 与 `--static=leaderboard-service` 并行同跑时共享 .m2-repo，父 `sport-verify-parent.pom` install 目标冲突致 leaderboard 段 `maven-install-plugin` 失败 rc=1（record 段先成功）。定界为并发 artifact（非静态违规），串行复跑 leaderboard rc=0 clean。规范：静态双模块须串行。
3. **D3 探针读数口径**：六探针 CI 等效以 offline 全量内六探针类全绿为口径（round-1 配方逐档复跑的等价收敛），共享中间件容器全程未停；本机中间件实况不作 PASS 依据（探针经 middle-service 隔离属性/mock 在 BOTH CI 与离线两口径均绿）。

## 9. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| v3 改派笔 | `e9d8898282bbe3e63abd3d26491c7f3b28df4d25`（`e9d8898`） | `docs(spec): 改派TASK-200纳入NotificationController同构构造修复（TASK-200）` |
| C-01 实施 | `fd1e53b3eadaf4ecfa189d9cccb356fe6ab7d6d7`（`fd1e53b`） | `fix(boot): 修复D3四缺陷恢复四服务生产实起（TASK-200）` |
| C-02 台账收口 | 本笔（自指，显式哈希以回传报告给出） | `docs(mailbox): 登记TASK-200生产启动缺陷修复验收与台账闭环（TASK-200）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 0`（v3 改派笔后头三笔未推送）→ C-01 后 / C-02 后逐笔递增。push 由指导侧另行授权执行，执行侧不推送不建 PR。

## 10. 未覆盖项与后续项

- **外部终验**：push 后第 41 次外部门槛 CI 全绿为终验——6 镜像构建 + Trivy 收缩态（`com.alibaba:fastjson:2.0.65` 不得触发 CRITICAL，若判 CRITICAL 走 v3 §2.6 决策树回报，禁自行登记 .trivyignore）+ 六探针实跑全绿；红则按签名归因、禁重试刷绿。
- **后续项（既有登记不变）**：rocketmq-spring 2.3.1 fastjson 类加载兼容（已以 v1 兼容包 2.0.65 回补受控发布，版本偏差登记 v3 §2.2）；其余既有后续项在 PLAN 在案，本课题未搭车。
- **临时件清理**：`.trae/tmp/` 下 round-2/round-3 冒烟日志、build 日志、契约日志、token 测量件等在回传后续清除（读数为本 handoff 留证为准）。
