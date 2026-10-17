# TASK-259 立项任务书（批 G-1：G1 时区语义全链统一）

> 状态：**已终裁 ACCEPT（2026-10-17 指导侧终裁，完整闭环待 CI 外部终验）**：实施（C-01 `48a0a45` 恰 3 文件 +243/−14 / C-02 `692073b` 恰 2 文件 +53/−16）→ 指导亲核三块 PASS（偏差①②追认 + N-1 C-02 行数笔误登记）→ 独立复核五块全 PASS 零阻断（七门禁独立复现全绿、白名单双向全等、UTC runner 反向实测 308 全绿双向时区无关性坐实、N-1~N-6 非阻断登记）；偏差① validateTimeRationality 比对帧三元分支（推导值东八帧/显式值 JVM 帧）与偏差② compose 两文件 CI 权威口径均追认；CI 外部终验读数由补记笔登记（随下一批次推送）

## 1. 课题背景与证据（指导侧亲验 8 处全部坐实）

G 系侦察报告 G1（BACKLOG 2026-10-17 批）：全系统时间语义三帧分裂——存储层意图东八、服务 JVM=UTC、前端按浏览器本地解析，同库两帧混存 8h 错位。

亲验证据链（行号级）：
1. SportRecordService.java L226 `record.setCreatedAt(LocalDateTime.now())`——JVM 默认时区墙钟（容器无 TZ → UTC）；
2. SportRecordService.java L1068-1069 `LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC)`——start_time/end_time 显式 UTC 推导，与 created_at 意图分裂（宿主机东八开发时同库混存 8h；compose 下两者均 UTC 但与 JDBC 意图分裂）；
3. 六服务 Dockerfile 无 TZ（record-service 抽查：eclipse-temurin:21-jre 默认 UTC）；docker-compose.services.yml 全文 TZ 零匹配——容器 JVM=UTC 坐实；
4. docker-compose.yml L46/L139 mysql、postgis 均 `TZ: Asia/Shanghai`——中间件声明东八而六个 Java 服务无——意图分裂坐实；
5. JDBC `serverTimezone=Asia/Shanghai` 全仓 7 处一致（compose user/verify/record 注入 + user/verify/leaderboard yml + record sharding.yaml）——存储层意图=东八，与 JVM（UTC）分裂坐实；
6. 归日消费链：myStreak L874-882（anchor.toLocalDate() + LocalDate.now()）、myStats L937-945（SQL COALESCE + JVM toLocalDate 双口径）、GoalService L194-199（COALESCE 窗口）——全部依赖 JVM 默认时区，compose 下按 UTC 日归日（北京 08:00 前晨跑归前一日，影响打卡断签/目标进度/周期榜窗口）；
7. feed.page.vue L169 `new Date(iso)`——无 offset ISO 串按浏览器本地解析（东八用户把服务端 UTC 墙钟当本地墙钟，相对时间偏 8h）；leaderboard.page.vue L72-83 浏览器本地日构造周一/月初窗口传服务端——窗口边界错位。

缺陷影响：打卡断签判定（myStreak）、目标进度窗口（GoalService）、周期榜窗口（range）、全部时间显示系统性偏移。

## 2. 改动白名单（仅限下列文件，双向全等）

1. `docker-compose.services.yml`（六服务 environment 各注入 `TZ: Asia/Shanghai`）
2. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R2 推导改区）
3. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`（R3 断言适配 + R4 边界用例，允许并入本文件；若新建测试文件须在 C-01 前登记增补白名单）
4. 台账：`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`、`work/mailbox/tasks/TASK-259/spec.md`、`work/mailbox/tasks/TASK-259/handoff.md`

零触碰面：api 模块、其余五服务 src/test（user/verify/leaderboard/mapmatch/gateway）、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose.yml（中间件文件）、.github/、三看板其余内容。

## 3. 修法要求

- **R1 容器时区统一**：docker-compose.services.yml 六服务（gateway/user/record/verify/leaderboard/mapmatch）environment 各加一行 `TZ: Asia/Shanghai`（注释标注 TASK-259 口径：与 mysql/postgis 中间件及 JDBC serverTimezone 对齐；本地/CI 零影响——TZ 仅容器环境变量）。
- **R2 推导显式改区**：SportRecordService L1068-1069 `ZoneOffset.UTC` → `ZoneId.of("Asia/Shanghai")`，以类内 `private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai")` 常量承载（Javadoc 声明全链东八墙钟口径：容器 TZ/JDBC serverTimezone/前端目标用户）。仅此一处生产代码改动，其余 LocalDateTime.now() 落点经容器 TZ 统一后自动正确。
- **R3 既有断言适配**：SportRecordServiceTest L530-532 两断言期望值 `ZoneOffset.UTC` → 与 R2 同口径（向演进同步断言非删弱，登记 handoff §4；其余 setStartTime 均入参构造自洽零改动）。
- **R4 预注册边界用例**（固定 Instant + 显式 ZoneId 构造，runner 时区无关，≥4 条）：
  1. 跨日提交：ts=UTC 前一日 16:00Z（=东八当日 00:00）——start_time 归东八当日；
  2. 东八 23:59 边界：提交时刻归当天、+2 分钟归次日；
  3. 晨跑归日：东八 07:00（UTC 前一日 23:00）轨迹 myStreak 归东八当日（修前 compose 口径归前一日）；
  4. 周/月窗口对齐：GoalService/周期榜窗口以东八日界过滤。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），触碰 compose 基建 → 七模块全量逐位验证，surefire 基线 63/78/215/302/200/108/35 只增不减（record 允许 302→增加，禁止减少；R3 适配不删测）；
- `docker compose -f docker-compose.services.yml config` 校验通过（六服务 TZ 注入生效的配置层证据）；
- 契约门（`--open=TASK-259 --baseline=<开工基线>`，handoff §2 逐行列全改动集）、token 门 29 项 `git grep -cF` 只增不减、词面门模式文件法+探针阳性对照、checkstyle delta=0、`git diff --check` 净；
- 双提交：C-01 业务+测试+compose / C-02 台账；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记，如实回报）

1. 真实容器 JVM `user.timezone` 读数未实测——TZ 环境变量→JVM 默认时区为 JDK 标准行为 + temurin ubuntu 基础镜像含 tzdata，以 compose config 配置层断言承载，运行态读数登记受限；
2. 存量数据不迁移——compose 形态存量 UTC 墙钟与新东八墙钟混存，演示库可接受，不做迁移脚本；
3. 非东八浏览器用户显示错位——目标用户东八，登记已知边界；
4. Nacos 运行时配置未核（沿 G 系侦察口径）。

## 6. 验收判据

C-01+C-02 落库、七门禁全绿、白名单双向全等、边界用例 ≥4 条全绿、独立复核五块 PASS 后指导侧终裁；CI 外部终验读数由补记笔登记（随下一批次推送）。
