# TASK-262 执行交接（handoff）

> 状态：**已终裁 ACCEPT 封盘**（2026-10-17 指导侧终裁；亲核三块 + 独立复核五块 PASS 零阻断，七门禁全绿，批次推送后 CI 外部终验待补记笔登记）

## 1. 开工基线

- 开工基线：main @ `6f7f2cc642778e619c644ddbb6663adb7930a28b`（TASK-262 立项笔；`git rev-parse HEAD` 实测逐位一致；开工时 `git status --porcelain` 实测为空）
- 领先笔数实测：`git rev-list --count origin/main..HEAD` = **2 笔**（TASK-261 补记笔 `54e3c50` + 本笔立项笔 `6f7f2cc`），与派发书口径一致；禁推送约束不变，本笔零推送（推送由指导侧批次统一执行）。
- 任务书：`work/mailbox/tasks/TASK-262/spec.md`（白名单/修法/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 6f7f2cc` 双向全等）

- 改动清单（26 项 = C-01 业务/测试 25 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/main/java/com/sportverify/record/controller/RecordController.java
  - record-service/src/main/java/com/sportverify/record/controller/LikeController.java
  - record-service/src/main/java/com/sportverify/record/controller/GoalController.java
  - record-service/src/main/java/com/sportverify/record/controller/InternalRecordController.java
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
  - record-service/src/test/java/com/sportverify/record/controller/LikeControllerTest.java
  - record-service/src/test/java/com/sportverify/record/controller/GoalControllerTest.java
  - record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
  - user-service/src/main/java/com/sportverify/user/controller/UserController.java
  - user-service/src/main/java/com/sportverify/user/controller/NotificationController.java
  - user-service/src/main/java/com/sportverify/user/controller/FriendController.java
  - user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
  - user-service/src/main/java/com/sportverify/user/mq/LikeEventConsumer.java
  - user-service/src/main/java/com/sportverify/user/mq/NotificationEventConsumer.java
  - user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java
  - user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/config/HierarchicalCacheRedisRoundTripTest.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/controller/LeaderboardControllerDailyTest.java
  - verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java
  - verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
  - api/src/main/java/com/sportverify/api/record/RecordApi.java
  - api/src/main/java/com/sportverify/api/record/RecordApiFallback.java
  - work/mailbox/tasks/TASK-262/handoff.md
- 未跟踪文件核验（C-01 前 `git ls-files -o --exclude-standard`）：实测为空；业务/测试 25 项全为既有文件原地修改，零新建；`.trae/tmp` 临时件属 gitignore 不纳入比对。
- spec §2 白名单双向全等自证：白名单 1-23 项业务落点逐项落改（C-01 恰 25 文件，为白名单条目 16 与 19 两处执行侧选定落点的必要展开——条目 16 user 测试落点 = 上方清单第 16、17 行两文件（Auth 与 Notification 控制器测试），条目 19 leaderboard 测试落点 = 第 20、21 行两文件（二级缓存往返测试 + 榜单控制器日汇总测试），两落点均在 C-01 前定档并登记于此）；台账项由本笔 C-02 只改本文件，另三本台账零动（沿 TASK-259/260 先例由指导侧封盘笔滚动）；白名单外全仓零 diff（common 模块、gateway/mapmatch 服务、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose*.yml、状态机/MQ/批量/索引参数零触碰）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（终树第 7 跑；第 5 跑因 L2 键前缀缺 `::` 分隔符仅 leaderboard 单测 1 项失败，修正后第 6/7 跑同读数，第 7 跑为 javadoc 句点收尾修订后的终树读数）；surefire **63/78/219/320/203/110/35**（common/gateway/user/record/verify/leaderboard/mapmatch）——基线 63/78/218/318/202/109/35 只增不减：user +1（Auth 403 delta 契约用例）、record 318→320 净 +2（删 2 个 listPoints 用例，新增 4 用例：PageResult 三形态 + record 404 delta）、verify +1（outbox SQL 整文本等价断言）、leaderboard +1（TTL 抖动值域断言）；零删减零跳过；mapmatch 35 持平（本笔零 mapmatch 文件，照跑） |
| 2 | 契约门 | `mailbox-contract.sh --open=TASK-262 --baseline=6f7f2cc` | 0 | 三时点实测：C-01 前工作树——TASK-262 回传文件无基线变动，判据 A 两件套齐、判据 B 不重审 rc=0；C-01 后（`3379843`）同形态 rc=0；C-02 前在途态与 C-02 后终态——判据 B 26 路径双向全等（清单多报 0、改动集未声明 0）rc=0 |
| 3 | token 门 29 项 | `git grep -cF` 逐项（清单与口径沿 TASK-181 handoff §6 / TASK-257；全仓 tracked 匹配行累加） | 0 | 终树复测 SUM = **2075** = 派发书期望绝对口径（2046 + 29）；29 项逐项只增不减且全部持平（零增减）；明细不重复枚举（承载表见 TASK-261 §3，本档零自枚举以避免 +29 叠加） |
| 4 | 词面门（模式文件法 + 探针阳性对照） | `git grep -n -I -iE -f <模式文件>`（四 locale，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1（终树复测）；模式文件 od 实测 LF 无 \r；阳性探针（仓根 untracked 文件、非命令行中文）`--untracked` rc=0 恰命中后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 5 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` + 纯净 worktree（`.trae/tmp/task262-baseline` @ 6f7f2cc）双向对照 | 逐模块（leaderboard 0；含存量违规者 1） | 五模块双向：record 4 = 4（SportRecordService JavadocType 既有违规随 R6 删除行位移，同集合）；leaderboard 0 = 0（零违规，rc=0）；user 1172→1142 净减 30；verify 976→972 净减 4；api 507→502 净减 5。逐文件归一比对**零文件增加**；新增行违规交集为空（verify 侧 diff 重排命中的 10 处经核为同一 javadoc 行既有违规误报，该文件计数 29→25 净减）。含存量违规模块 checkstyle 以 1 退出系存量面，非本笔新增（沿 TASK-254/255/259/260/261 先例） |
| 6 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（rc=0）；25 个修改文件工作树行尾保持既有形态（16 LF + 9 CRLF），committed blob 恒 i/lf 与 CI 编译物一致；零新建文件（新文件 LF 判据本笔不适用）；`.mvn-settings.xml` 为 gitignore 本地件未入库 |
| 7 | mapmatch 照跑 | offline 全量内含 | 0 | mapmatch **35 持平**（本笔零 mapmatch 文件；BUILD SUCCESS 内含） |

## 4. 条件项与非阻断登记（如实回填）

- **R1 两类契约 delta 用例落点**：①record 3001→404 = RecordControllerTest 的 standalone MockMvc 全链路用例（公共 advice 接管，断言 HTTP 404 + body code 3001）；②Auth 1002→403 = AuthControllerTest 的 standalone MockMvc 全链路用例（AuthService 打桩抛锁定语义，断言 HTTP 403 + body code 1002）。11 个局部 handler 用例改写分布 = RecordControllerTest 3 / LikeControllerTest 3 / GoalControllerTest 3 / NotificationControllerTest 2，断言从「局部 handler 恒 200」升级为「公共层 HTTP 状态 + body 双断言」，另 LeaderboardControllerDailyTest 的 standalone 桩增公共层 advice 注册。spec §3 R1 要求的「局部 handler 内 log.warn 域化文案随删除收敛公共层统一文案」已确认并登记为有意收敛（公共层集中统一日志规约）。
- **R1 局部 handler 处置边界**：GoalController 的 `IllegalArgumentException` 局部 handler 保留——不在 spec 八处 `BizException` 清单内，公共层 L171 虽已有 `@ExceptionHandler(IllegalArgumentException.class)` 映射 400，但本课题对该局部 handler 保持面外零触碰/冗余保留，零 delta。
- **R2 抖动形态登记**：采用 spring-data-redis 3.2.4 的 `RedisCacheWriter.TtlFunction`（该版本无顶层 TtlFunction，实测 API 签名 `getTimeToLive(key, value) -> Duration`）；基值 5min ± 60s 均匀抖动（`nextLong(-60, 61)`），L1 Caffeine 恒定 5min 不抖动；值域断言 1000 次取样全落 [240s,360s] 且去重值 >2。
- **R4 常量落点登记**：`"notification:event:"` 前缀收拢为 NotificationEventConsumer 的包级可见 `DEDUP_KEY_PREFIX`，LikeEventConsumer 跨类引用（同包、零新增文件）；outbox 状态三值收拢为 VerifyEventOutboxMapper 接口常量 `SQL_STATUS_PENDING/SENT/EXHAUSTED`（单引号形态入常量，注解 SQL 拼接后逐字等价），等价性由既有 SQL 契约测试（selectPendingBatch）与新增六语句整文本断言共同钉守（8 语句中 7 句有整文本断言，selectExhausted 经人工双向 diff 文本比对确证逐字未变）。
- **R5 序列化形态与升版登记**：L2 值序列化改为 GenericJackson2JsonRedisSerializer，typing 定制为 `As.WRAPPER_ARRAY` 根级对称包裹——默认 `As.PROPERTY` 对根级 List 写侧不落类型包裹、读侧按数组回读，往返不对称（本批 offline 实测坐实后修正）；L2 物理键前缀升版为 `:v2::`（Spring cache 名保持 leaderboard:overall 不变，故 `@Cacheable` 与 LeaderboardService 白名单外零改动），旧 JDK 条目 5min TTL 内自然过期孤儿化；往返断言覆盖 JSON 首字节（非 JDK 魔数）、生产序列化器回读等价、BigDecimal scale 存活与新实例 L2 命中不回源。
- **R6 测试处置对账（record surefire 只增不减红线）**：删除 = SportRecordServiceTest 两例（listPoints 路由、归档冷热判别式）+ VerifyServiceTest 一处针对已删契约的 `never()` 断言（非用例删除）；新增 = record 4 例（/my、/feed、/{id}/points 的 PageResult 序列化形态 + record 404 delta 契约）、user 1 例、verify 1 例、leaderboard 1 例；record 318→320 净 +2，等量补位成立且只增不减。
- **R3 薄转换登记**：RecordController 单点私有 `toPageResult(Page)` 转换（current/size/total/records 语义对齐），Service 层返回 `Page` 零改动；三端点形态断言以 standalone MockMvc 序列化结果为准，断言四字段值且 JSON 不含 `pages` 实现细节字段。
- **白名单文件数差异登记（非阻断）**：派发书「C-01 恰 23 文件」按白名单条目计；spec §2 条目 16、19 的「...」占位由执行侧选定并各自展开为 2 个既有测试文件（R1 四类控制器测试与 R2/R5 断言无法并入同一文件），故 C-01 实际 25 文件，全部落在白名单条目授权面内，无面外文件。
- **提交形式与路径笔误说明（非阻断）**：实施两笔提交全角括号（TASK-262）与 C-01 scope=record 跨服务均沿仓内先例合规追认；spec §2 条目 13 AuthController 路径笔误（缺 auth/）已在封盘笔更正，实施改动完全在语义授权面内。
- **受限声明沿 spec §5 五项**：G6 前端透明性为 client.ts 静态核对；G12 jitter 收益为范式对齐推论（单 key 无批量过期面）；G16 旧条目孤儿化依赖 5min TTL 自然过期、无真实 Redis 混存实测；G17 死面判定为静态 grep 依据；Nacos 运行时配置未核。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件（offline 七轮日志 / static 双向日志与结果件 / token 清单与读数 / 词面模式与探针 / msg 两笔 / 基线纯净 worktree 已 `git worktree remove`）收口清理。
- 未推送：`origin/main..HEAD` 收口后 = **4 笔**（`54e3c50` + `6f7f2cc` + C-01 + C-02）——推送由指导侧批次统一执行，本笔零推送。
- spec/handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 26 路径双向全等）。
