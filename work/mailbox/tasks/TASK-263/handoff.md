# TASK-263 执行交接（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-17 指导侧终裁；开工基线 = main @ 76a447b119566fa5c44cfe77a6e88bbed66ac131；MEDIUM 档，指导亲核三块 PASS 零阻断，七门禁全绿，封盘批次推送 origin/main）

## 1. 开工基线

- 开工基线：main @ `76a447b119566fa5c44cfe77a6e88bbed66ac131`（TASK-263 立项笔；`git rev-parse HEAD` 实测逐位一致；开工时 `git status --porcelain` 实测为空）
- 领先笔数实测：`git rev-list --count origin/main..HEAD` = **2 笔**（TASK-262 补记笔 `e2df483` + 本笔立项笔 `76a447b`），禁单独推送，由指导侧批次统一执行。
- 任务书：`work/mailbox/tasks/TASK-263/spec.md`（白名单/修法/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 76a447b` 双向全等）

- 改动清单（13 项 = C-01 业务/配置/测试 12 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - .github/workflows/ci.yml
  - leaderboard-service/src/main/resources/application.yml
  - record-service/src/main/resources/application.properties
  - user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java
  - user-service/src/main/java/com/sportverify/user/entity/User.java
  - user-service/src/main/resources/application.yml
  - user-service/src/test/java/com/sportverify/user/config/RedisTimeoutConfigTest.java
  - user-service/src/test/java/com/sportverify/user/config/StompConnectAuthInterceptorTest.java
  - user-service/src/test/java/com/sportverify/user/entity/UserJsonIgnoreTest.java
  - verify-service/src/main/resources/application.yml
  - web/src/pages/notifications.page.vue
  - web/vite.config.ts
  - work/mailbox/tasks/TASK-263/handoff.md
- 零触碰面声明：未触碰 gateway/mapmatch 服务，未触碰 common/api 模块，未触碰数据库 DDL 与迁移脚本，未触碰业务服务核心逻辑，未触碰各 pom 与 package 锁文件，未触碰三本台账。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（09:03 min）；surefire **63/78/221/320/203/110/35**（common/gateway/user/record/verify/leaderboard/mapmatch）——基线 63/78/219/320/203/110/35 只增不减：user 219→221 净 +2（新增 RedisTimeoutConfigTest 1 测 + UserJsonIgnoreTest 1 测），其余六模块严格持平，零失败零跳过 |
| 2 | 前端三门禁 | `pnpm type-check` / `pnpm test` / `pnpm build` | 0 | vitest 4 套 63 测全绿（2.37s）；type-check 退出码 0；build 成功，分包后 vendor-vue 131.62KB、vendor-antdv 1417.38KB、主 index chunk 降至 90.64KB（远低于 500KB 门禁红线）；`git diff --exit-code -- web/src/typed-router.d.ts` 纯净保持 rc=0 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-263 --baseline=76a447b` | 0 | 判据 A 两件套齐；判据 B 13 路径双向全等（清单多报 0、改动集未声明 0） |
| 4 | token 门 29 项 | `git grep -cF` 逐项（全仓 tracked 累加口径） | 0 | 终树实测 SUM = **2104** = 派发书期望绝对口径（2075 + 29）；29 项逐位只增不减（因本 handoff 自枚举效应各 +1）：13.4=157、18.0=173、73.93=114、68.8=109、6315=89、1.8612=88、3.3066=88、5.7056=94、9.408=79、36525962432=72、36586847965=69、36438897772=71、36399582548=69、36098038547=67、2806=109、598=106、36736221648=66、36808102571=54、36821040708=48、36845152965=45、36871294588=43、36880083885=45、36958994260=44、36976873215=39、36992632143=36、36995450125=34、37008317295=33、37021305016=32、37591580687=31 |
| 5 | 词面门 | `git grep -n -I -iE -f <模式文件>`（CI 8 禁词权威口径，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；模式文件 od 实测 LF 无 \r；阳性探针 probe263.txt `--untracked` rc=0 恰命中后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` + 纯净 worktree 双向对照 | 1（存量形态） | user-service 工作树 1142 违规 = 基线 76a447b 纯净 worktree 1142 违规（delta=0，零新增）；新增与改动 Java 文件无新增违规，StompConnectAuthInterceptor、User、StompConnectAuthInterceptorTest、RedisTimeoutConfigTest、UserJsonIgnoreTest 零新增违规 |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（rc=0）；修改文件保持既有行尾，新建 2 个测试文件纯 LF + 末字节 0x0A 结尾 |

## 4. 条件项与非阻断登记（如实回填）

- **R1 Redis 超时矩阵落地**：四个微服务均配置 3000ms（YML 为 `spring.data.redis.timeout: 3000ms`，properties 为 `spring.data.redis.timeout=3000ms`）；测试通过 `RedisTimeoutConfigTest` 加载配置并断言配置字面量与 Binder 绑定 Duration 均为 3000ms，快速轻量且不依赖真实 Redis。
- **R2 CI web job pnpm 缓存落地**：在 `.github/workflows/ci.yml` web job 中对齐 build job 范式，在 Enable pnpm 后加入 `actions/cache@v5` 缓存 `~/.local/share/pnpm/store`，key 绑定 runner.os 与 lockfile 哈希。
- **R3 notifications 串行瀑布消除**：`onBatchMarkRead`、`onMarkAll`、`onClearRead` 三处统一改为 `await Promise.all([loadList(), refreshUnread()])`，减少一次网络 RTT 延迟。
- **R4 Vite 分包与 chunk 优化**：`vite.config.ts` 中配置 manualChunks 将 `ant-design-vue` 与 `@ant-design/icons-vue` 划入 `vendor-antdv`，将 `vue`、`vue-router`、`@tanstack/vue-query` 划入 `vendor-vue`；主 chunk 体积由 1.64MB 大幅下降至 90.64KB，彻底解决主 index chunk 超过 500KB 问题，同时 `typed-router.d.ts` 保持纯净无漂移。
- **R5 STOMP 鉴权异常保留 cause**：`StompConnectAuthInterceptor` 中将捕获的底层异常 `e` 传入 `new MessagingException(..., e)`，单测中针对过期与无效签名 token 补齐 `assertNotNull(ex.getCause())` 断言。
- **R6 User passwordHash 防御性注解**：`User.java` 的 `passwordHash` 字段增加 `@JsonIgnore` 注解，单测 `UserJsonIgnoreTest` 验证 Jackson 序列化输出不含 passwordHash 与敏感哈希值。
- **受限声明沿 spec §5 五项**：G22 Hikari 连接池容量保持默认 10；G23 慢 SQL 依托现有多数据源日志与底层 slow log；G8 Redis 超时为生产防御性标称值无网络丢包故障注入；G20 pnpm 远端缓存以 Actions CI 运行为准；Nacos 运行时配置未核。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件收口清理。
- 未推送：`origin/main..HEAD` 收口后 = **4 笔**（TASK-262 补记笔 `e2df483` + 立项笔 `76a447b` + C-01 `3c6a596` + C-02）——推送由指导侧批次统一执行，本笔严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 13 路径双向全等）。
