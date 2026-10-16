# TASK-253 任务书（spec.md）

> **课题**：E 系批3——数据一致性批：E5 调度器单线程饿死 + E6 申诉终判跨服务偏斜无补偿 + E7 结算 ZREM 并发误删 + E9 archived 列迁移漂移与归档分片缺索引
> **级别**：FULL 档（E6 申诉终判补偿与 E7 结算纠偏均为核心服务逻辑生产改动；多模块 record/verify/leaderboard + sql/migrations 基建 → 独立复核 + 全量逐位离线门禁）
> **责任服务**：`record-service`（yml 配置）、`verify-service`（对账新类）、`leaderboard-service`（结算复核）、`sql/migrations`
> **开工基线**：`c020587`（main，工作树干净；C-02 已回填实际哈希）
> **状态**：已实施（C-01/C-02 双笔收口，七门禁读数与受限登记见 handoff.md）

---

## 1. 背景与目标

E 系只读立项侦察（2026-10-16 归档，BACKLOG.md E 系节）批3 四项，指导侧立项侦察亲验坐实：

- **E5**：record-service 恰 4 个 `@Scheduled`（`RecordLikeService.flushPendingLikes` L419 / `RecordLikeService.reconcileLikeCounts` L515 / `VerifyDegradeService` L97 / `TrackPointArchiveService.archiveSweep` L81）共用 Spring Boot 默认**单线程**调度器（application.yml 全仓 grep `task|scheduling|pool` 零命中）；`archiveSweep` 无 initialDelay（默认 0 **启动即跑**）且首轮逐记录迁移（读热表点 → 批插归档 → 删热表 → 置标志），可长时间独占唯一线程，饿死 flush（秒级高频）/对账/降级探测；
- **E6**：申诉终判链（`VerifyService.processAppeal`）顺序为「L260 本地事务终判 appeal + outbox 事件 → L276 verdict 回写 → L282 `recordApi.getRecord` → L289 `callbackStatus`（Feign statusCallback）」——本地事务已提交后任一 Feign 失败，record **永久卡 APPEALING**：record-service 无任何 verify 事件消费者（全仓仅 LikeEventProducer/RecordEventProducer 两 Producer，grep 亲验零命中），状态迁移唯一路径是同步 Feign，无补偿；且 `reconcileCallback` L168 白名单仅 VERIFYING/MANUAL_REVIEW 不含 APPEALING，兜底面也不覆盖；
- **E7**：`settleAndReconcile` L474 先 `selectActiveSummaries` 采 DB target，L487-513 scan ZSet 对「target 缺失」成员批式 ZREM——竞态：`applyVerified`（L172 **先写 DB contribution、L183 后 ZINCRBY**）在结算 SELECT 之后完成 DB 写、scan 时已入 ZSet 的成员，因不在 target 而被误 ZREM，**掉榜至下一轮结算（10min）**；L444 注释已登记该权衡（「并发增量被下一轮结算覆盖收敛」），窗口为整个结算期（scan 全量遍历秒级）；
- **E9**：`sql/05-track-point-archive-shards.sql` L238-240 是 `sport_record.archived` 列 + `idx_archive` 索引全仓唯一出现处，注释自证「非幂等：既有库手动执行一次」；`scripts/db/migrate.sh` 全量重跑 `sql/migrations/*.sql` 而 migrations 目录无该列迁移 → 05 引入前初始化的存量库升级后 `TrackPointArchiveService.sweepOnce`（`eq(SportRecord::getArchived, 0)`）查询即 BadSqlGrammar；另 `add-idx-record-seq.sql` 覆盖热表 17 条却无 track_point_archive 分片（分片表仅 `idx_record(record_id)` 单列，缺 `(record_id, seq)` 复合）。

## 2. 严格改动文件白名单（恰 10 项，禁超出）

1. `record-service/src/main/resources/application.yml`（R1 调度池配置）
2. `verify-service/src/main/java/com/sportverify/verify/service/AppealReconcileService.java`（R2 新建兜底对账服务）
3. `verify-service/src/main/java/com/sportverify/verify/mapper/AppealMapper.java`（R2 新查询方法）
4. `verify-service/src/test/java/com/sportverify/verify/service/AppealReconcileServiceTest.java`（R2 新建单测）
5. `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`（R3 新复核查询）
6. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`（R3 ZREM 二次复核改造 + L444 注释更新）
7. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`（R3 用例增补与既有断言适配；类名/路径以仓内实际为准，若结算用例落在其他测试类则按实际）
8. `sql/migrations/add-track-archive-column-and-idx.sql`（R4 新建幂等迁移）
9. `work/mailbox/tasks/TASK-253/spec.md`（C-02 回填占位符与状态）
10. `work/mailbox/tasks/TASK-253/handoff.md`（C-02 回填五节）

## 3. 四判别式实现契约

### R1（E5）调度线程池并行化

- `record-service/src/main/resources/application.yml` 新增：

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 4   # 4 个 @Scheduled 各占一线程：archiveSweep 启动即跑的长任务不再饿死 flush/对账/降级探测
```

（缩进与既有 spring 段落合并，不重复建顶层 key；注释注明 4 任务 4 线程的对应关系）
- 禁改任何 Java（零代码改动）；不引入 `@EnableAsync`/`SchedulingConfigurer` 自定义配置类；
- **受限登记**（handoff §4 必落）：并行化后 flush ∥ reconcile 在单实例也并发——该竞态与多实例生产姿态同口径（flush 无锁、多实例天然并行，reconcile 的 Redisson 锁只防多实例 reconcile 互斥），窗口内 reconcile SET 可能短暂覆盖 flush INCR 的计数，由下轮对账自愈（10min 内），非新缺陷类别。

### R2（E6）申诉终判偏斜兜底对账

- 新建 `AppealReconcileService`（verify-service，`@Service` + `@Scheduled`）：
  - 周期：`fixedDelay` 10min + `initialDelay` 2min（给启动后同步回调留窗口）；
  - 防重：Redisson `tryLock`（沿 `settleAndReconcile`/`reconcileLikeCounts` 先例，锁 key `verify:appeal:reconcile`；锁服务异常跳过本轮，可用性优先）；
  - 扫描：`AppealMapper` 新方法 `selectFinalizedBatch(limit)`——`SELECT * FROM appeal WHERE status IN (1, 2) ORDER BY id DESC LIMIT #{limit}`（走既有 `idx_status_id`；终判为低频人工流，全量终判行倒序批扫即可，appeal 表无终判时间字段故不用时间窗口；limit 建议常量 100）；
  - 逐行修复：`recordApi.getRecord(recordId)` → record 状态**恰为 APPEALING** 才修复，其他任何状态（含已迁移 RE_PASSED/RE_CONFIRMED）跳过零回调；修复调用 `recordApi.statusCallback(recordId, StatusCallbackDTO(recordId, APPEALING, appeal.status==1 ? RE_PASSED : RE_CONFIRMED, record.version))`，非 0 响应或 Feign 异常 → 单行 catch + log.error 继续批内其余（下轮重扫自愈）；
  - 幂等闭环：record 端 statusCallback 乐观锁 fromStatus=APPEALING → 修复成功后 record 非 APPEALING，下轮扫描天然跳过；
  - Javadoc：注明与 `processAppeal` 的关系（同步回调失败仍抛异常供人工终判界面即时感知，行为不变；本服务只兜底历史偏斜，最长修复延迟 = 一个对账周期）；
- `VerifyService.processAppeal` 与 `callbackStatus` **零改动**；
- 新建 `AppealReconcileServiceTest`（纯 Mockito，沿 `RecordLikeServiceTest` 风格）至少五用例：① 偏斜对修复（appeal RE_PASSED + record APPEALING → statusCallback 恰一次且 fromStatus/toStatus/version 参数正确）；② record 已 RE_PASSED → 跳过零回调；③ record 其他终态 → 跳过零回调；④ 单行 Feign 异常 → log 后批内其余行仍处理；⑤ 锁未获取 → 本轮跳过零扫描。

### R3（E7）结算 ZREM 二次复核

- `LeaderboardContributionMapper` 新方法 `selectActiveUserIds(Collection<Long> userIds)`：`SELECT DISTINCT user_id FROM leaderboard_contribution WHERE status = #{status} AND user_id IN (...)`（`@script`/foreach 沿仓内既有注解模式）；
- `LeaderboardService` 改造：`flushStale`（或 scan 攒批处）在 ZREM 前对本批候选调 `selectActiveUserIds` 复核——**仍缺失者才 ZREM，复核命中者保留**；
  - 复核依据：`applyVerified` 先写 DB 后 ZINCRBY（L172→L183 亲验），故结算 SELECT 后入榜的成员在复核时**DB 必已落行**，复核可保住主竞态窗口的全部成员；
  - 失败语义：复核查询异常 → **保守跳过本批 ZREM**（log.warn，宁漏删不误删，漏删者下轮收敛）；
  - L444「已知权衡」注释更新：窗口由「结算期（秒级）」收窄为「复核 SELECT 与 ZREM 之间（毫秒级）」，残余仍由下一轮结算收敛；
- 既有结算用例断言适配（禁删弱）：时序桩改口径——至少四用例：① 候选复核时已在 DB → 保留不 ZREM；② 复核仍缺 → ZREM 执行（清理计数正确）；③ 复核异常 → 保守跳过零 ZREM；④ 既有孤儿回滚/汇总/截断/报表断言全保持；
- 硬约束遵守：service 单测桩 Mapper 合法（先例遍仓），非「Mockito 预制过滤列表做 SQL 红测试」。

### R4（E9）幂等迁移脚本

- 新建 `sql/migrations/add-track-archive-column-and-idx.sql`，`USE record_db;` 后两段：
  - **段 1**：`sport_record` 补 `archived` 列 + `idx_archive (archived, end_time)` 索引——幂等模式沿 `add-record-visibility.sql` 先例（`information_schema.columns` / `information_schema.statistics` 条件判断 + PREPARE/EXECUTE 动态 SQL；MySQL 8.0 无 ADD COLUMN IF NOT EXISTS）；列定义与 `05-track-point-archive-shards.sql` L239-240 **逐字一致**（双写约定同 add-record-visibility 先例：新装经 05 initdb 自动获得，存量库经本迁移获得）；
  - **段 2**：`track_point_archive_00` … `_15` 逐分片补 `idx_record_seq (record_id, seq)` 复合索引（16 段幂等判断，写法沿 `add-idx-record-seq.sql` 热表 17 分片先例形态；只加不删，既有 `idx_record` 冗余保留并在头注释登记）；
  - 文件头注释：背景（05 为 initdb 一次性非幂等脚本、migrate.sh 全量重跑需幂等）、订正来源 E9/TASK-253、双路径关系（新库 05 / 存量库本迁移 / 已按 05 注释手动执行过的库幂等跳过）；
- `sql/05-track-point-archive-shards.sql` **零触碰**（initdb 脚本，注释描述的手动路径仍真，新迁移文件头已注明关系）；
- `scripts/db/migrate.sh` **零触碰**；SQL 无纯 JVM 单测，验证 = 门禁 + 可选 scratch 库实测留证（受限登记）。

## 4. 本地门禁（FULL 档全量逐位，全部 rc=0 并留证）

1. **后端全模块**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test`——全模块逐位通过，测试数只增不减（基线：common 52 / gateway 71 / user 205 / record 300 / verify 182 / leaderboard 104 / mapmatch 29；verify 与 leaderboard 应有新增，其余模块凭白名单外零 diff 论证不变）；
2. **Compose 校验**：`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` rc=0（本地 .env 已含 INTERNAL_API_TOKEN）；
3. **前端回归**：`cd web; pnpm run type-check; pnpm test` 全绿（基线 4 文件 61 测）；
4. **契约门**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-253 --baseline=<开工基线>` rc=0；
5. **token 门**：29 项受保护 token 字面量 `git grep -cF` tracked 全量口径（清单权威来源 `work/mailbox/archive/PLAN-history-001-210.md` 受保护 token 节，Grep 定位读取，勿在台账枚举字面量原文），相对开工基线只增不减；
6. **词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机须模式文件法绕开中文 127：模式放 .trae/tmp/ 用后删，显式 -E + 合成探针阳性对照证明模式有效）；
7. **`git diff --check`**：提交前净；修改文件保持既有行尾形态，新文件纯 LF + 完整末尾换行。

## 5. 双提交规范

- **C-01 实施笔**：`fix(record,verify,leaderboard): 调度池并行化与申诉终判兜底对账及结算ZREM复核和归档列迁移（TASK-253）`——仅白名单文件 1-8；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-253实施完成与门禁回填（TASK-253）`——仅文件 9、10；回填 handoff 五节（实施摘要/只改清单全等声明/门禁读数含时点边界/受限声明/双提交哈希），并把两件套 `<立项笔哈希>` 占位符替换为实际哈希；
- 已知受限（handoff §4 须登记）：① R1 flush∥reconcile 并发与多实例姿态同口径、下轮对账自愈（窗口内计数可能短暂回退）；② R2 兜底为轮询对账非事件驱动（最长 10min 修复延迟）、终判同步失败仍抛异常人工感知行为不变；③ R3 二次复核收窄竞态至毫秒级残余、非完全消除，残余由下轮结算收敛；④ R4 迁移覆盖 migrate.sh 存量库路径、scratch 库实测留证未做（可选）、05 initdb 脚本零触碰。

## 6. 零触碰红线

- gateway-service / user-service / mapmatch-service 全部 Java 与配置绝对零触碰；
- verify-service 的 `VerifyService.java`、`VerifyOutboxRelay.java`、`VerifyEventProducer.java` 绝对零触碰；
- record-service / leaderboard-service 除白名单 1/5/6/7 外绝对零触碰；`TrackPointArchiveService.java`、`RecordLikeService.java` 零触碰（E5 修复面仅 yml；E7 修复面仅结算路径）；
- 根与所有子模块 pom.xml、web/package.json、docker-compose*、`.github/`、`scripts/verify/*`、`scripts/db/migrate.sh`、`sql/0*.sql` 全部既有 SQL、各服务 application.yml（record 除外）、`work/mailbox/PLAN.md`、`work/mailbox/HANDOVER.md` 绝对零触碰；
- 不改 index/状态机/批量大小/周期/MQ 参数（R2 的 10min/2min/limit=100 与 R3 的复核查询为本课题**新增**常量，非修改既有参数；结算 10min 周期、scan 批次 500、对账周期等既有参数不动）。
