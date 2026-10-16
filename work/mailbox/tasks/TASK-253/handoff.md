# TASK-253 台账（handoff）

> **TASK**：E 系批3——数据一致性批（E5 调度饿死 + E6 终判偏斜无补偿 + E7 结算误删 + E9 迁移漂移，FULL 档）
> **开工基线**：`c020587`（main，工作树干净；C-02 已回填实际哈希）
> **方案**：2026-10-16 E 系只读立项侦察批3 推荐序，用户拍板「按推荐」立项
> **状态**：已封盘（终裁 ACCEPT；终裁依据 = 实施回报 / 指导亲核三块 PASS / 独立复核五块全 PASS 零阻断（N1-N4 非阻断登记）三方闭环一致；封盘批次已推送，CI run `38879776160` conclusion=success 全 job 绿——`web` ✓27s / `build` ✓11m15s / probe-ubuntu26 skipped（if 门禁正确生效旁证），外部终验达成，读数由补记笔 2026-10-16 落库登记）

## 1. 实施摘要

四判别式逐项落地（与 spec §3 对应）：

- **R1（E5）调度线程池并行化**（spec §3 R1）：record-service 配置新增 `spring.task.scheduling.pool.size=4` 并注明 4 任务 4 线程对应关系（flushPendingLikes / reconcileLikeCounts / VerifyDegradeService / archiveSweep，archiveSweep 无 initialDelay 启动即跑的长任务不再饿死其余三项）。**零 Java 改动**；不引入 @EnableAsync / SchedulingConfigurer。
  - **路径条件项**：spec 白名单第 1 项写 `record-service/src/main/resources/application.yml`，仓内实际为 `application.properties`（文件头注释自证：ShardingSphere 5.4.1 snakeyaml 1.x 与 Spring Boot 3.2.4 YAML 需 snakeyaml 2.x 无法共存，本服务禁改回 yml）——R1 按实际落地于 `application.properties`，语义逐字等价（`spring.task.scheduling.pool.size` 属性键不变）。
- **R2（E6）申诉终判偏斜兜底对账**（spec §3 R2）：新建 `AppealReconcileService`（verify-service，@Service + @Scheduled fixedDelay 10min + initialDelay 2min）；Redisson tryLock（key `verify:appeal:reconcile`，锁未获取/锁服务异常均跳过本轮，可用性优先）；`AppealMapper.selectFinalizedBatch(limit=100)`（`status IN (1,2) ORDER BY id DESC`，走既有 idx_status_id）；逐行 `recordApi.getRecord` → 恰 APPEALING 才 statusCallback（fromStatus=APPEALING、toStatus 按 appeal.status 映射 RE_PASSED/RE_CONFIRMED、带 record.version 乐观锁），单行 Feign 异常/非 0 响应 catch + log.error 继续批内其余（下轮重扫自愈）；Javadoc 注明与 `VerifyService.reviewAppeal` 的关系（同步回调失败仍抛异常供终判界面即时感知，行为不变；本服务只兜底历史偏斜，最长修复延迟 = 一个对账周期）。`VerifyService` / `VerifyOutboxRelay` / `VerifyEventProducer` **零触碰**。新建 `AppealReconcileServiceTest` 六用例（偏斜 RE_PASSED 修复参数/RE_CONFIRMED 映射/已迁移跳过/其他终态跳过/单行失败继续批内/锁未获取跳过）。
- **R3（E7）结算 ZREM 二次复核**（spec §3 R3）：`LeaderboardContributionMapper` 新增 `selectActiveUserIds(status, userIds)`（`SELECT DISTINCT user_id ... status=ACTIVE AND user_id IN`，`<script>/foreach` 沿 RecordLikeMapper 先例）；`LeaderboardService.flushStale` 改造——ZREM 前对本批候选调复核查询，仍缺 ACTIVE 贡献才 ZREM、复核命中保留（applyVerified 先写 DB 后 ZINCRBY，结算 SELECT 后入榜者 DB 必已落行）；复核异常 → 保守跳过本批 ZREM（log.warn，宁漏删不误删，漏删者下轮收敛）；L444 已知权衡注释更新（竞态窗口由结算期秒级收窄为复核 SELECT 与 ZREM 之间毫秒级，残余仍由下一轮结算收敛）。`LeaderboardServiceTest` 增补四用例（复核命中保留/复核仍缺 ZREM/复核异常零 ZREM/既有孤儿回滚·汇总·截断·报表链全保持），既有用例断言零删除零弱化。
- **R4（E9）幂等迁移脚本**（spec §3 R4）：新建 `sql/migrations/add-track-archive-column-and-idx.sql`——`USE record_db;` 段 1 为 `sport_record.archived` 列 + `idx_archive (archived, end_time)` 索引（information_schema.columns/statistics 幂等判断 + PREPARE/EXECUTE，列/索引定义与 `05-track-point-archive-shards.sql` L239-240 逐字一致，双写约定沿 add-record-visibility 先例）；段 2 为 `track_point_archive_0..15` 逐分片补 `idx_record_seq (record_id, seq)`（16 段幂等，写法沿 add-idx-record-seq.sql 先例形态；只加不删，既有 idx_record 冗余保留并在头注释登记）；文件头注释含背景（05 为 initdb 一次性非幂等脚本、migrate.sh 全量重跑需幂等）、订正来源 E9/TASK-253、双路径关系（新库 05 / 存量库本迁移 / 已手动执行过的库幂等跳过）。`sql/05-track-point-archive-shards.sql` 与 `scripts/db/migrate.sh` **零触碰**。

## 2. 只改清单全等声明

`git diff --name-only c020587` + 未跟踪文件（无 .trae 残留）恰 10 项，与 spec §2 白名单双向全等（第 1 项为路径条件项，见 §1 R1 注明）：

- record-service/src/main/resources/application.properties（路径条件项：spec 书 yml 形态，仓内实为 properties，语义等价，详见 §1 R1）
- verify-service/src/main/java/com/sportverify/verify/service/AppealReconcileService.java（新建）
- verify-service/src/main/java/com/sportverify/verify/mapper/AppealMapper.java
- verify-service/src/test/java/com/sportverify/verify/service/AppealReconcileServiceTest.java（新建）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- sql/migrations/add-track-archive-column-and-idx.sql（新建）
- work/mailbox/tasks/TASK-253/spec.md
- work/mailbox/tasks/TASK-253/handoff.md

零触碰声明（白名单外全仓零 diff）：gateway/user/mapmatch 全部 Java 与配置；verify-service 的 VerifyService / VerifyOutboxRelay / VerifyEventProducer；record-service 的 TrackPointArchiveService / RecordLikeService 及除上述 properties 外全部；leaderboard-service 除上述 3 项外全部；根与所有子模块 pom.xml、web/package.json、docker-compose*、.github/、scripts/verify/*、scripts/db/migrate.sh、sql/0*.sql 全部既有 SQL、record 外各服务 application.yml、work/mailbox/PLAN.md、work/mailbox/HANDOVER.md。测试类名/路径与 spec 假设逐位一致（LeaderboardServiceTest 即结算用例实际落点，无跨类迁移）。

## 3. 门禁读数

七门禁逐项（全部 rc=0，留证）：

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端全模块 | `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` | 0 | 全模块 surefire：common 52 / gateway 71 / user 205 / record 300 / **verify 188（+6）** / **leaderboard 108（+4）** / mapmatch 29；verify 新增 6 测 = AppealReconcileServiceTest，leaderboard 新增 4 测 = R3 复核四用例；common/gateway/user/record/mapmatch 逐位持平（凭白名单外零 diff 论证不变）；BUILD SUCCESS |
| 2 | Compose | `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` | 0 | 编排解析通过（本地 .env 含 INTERNAL_API_TOKEN） |
| 3 | 前端 | `cd web; pnpm run type-check; pnpm test` | 0 | type-check 0 错；vitest 4 文件 61 测全绿（与基线 4/61 持平，白名单无 web 文件） |
| 4 | 契约门 | `& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-253 --baseline=c020587` | 0 | 判据 A 两件套齐；判据 B 只改清单与实际改动集 10/10 全等（收口后终读，见时点边界） |
| 5 | token 门 | `git grep -cF` tracked 全量口径 29 项两时点 | 0 | 开工基线（c020587）→ 收口复测：28 项逐位持平、1 项 +1（新增单测行里程值子串命中），**无一减少**；新文件三件零 token 命中（提交前后口径一致）；token 字面量清单取自 PLAN-history 受保护 token 节，台账不枚举字面量 |
| 6 | 词面门 | CI 权威口径 `git grep -n -I -i -E -f <模式文件>`（模式文件法绕开中文 127，模式放 .trae/tmp/ 用后删，显式 -E） | 1（零命中） | 实扫 ZERO_HIT rc=1；合成探针阳性对照 HIT rc=0（.trae/tmp/probe 文件经 --no-index 实扫命中，证明模式文件有效）；临时件已删除 |
| 7 | diff 净 | `git diff --check` | 0 | 提交前净；行尾：既有文件保持各自形态（git diff --numstat 纯内容增量零行尾 churn），新文件三件纯 LF + 完整末尾换行 |

**时点边界说明**：门禁 1/2/3/5/6/7 为 C-01 前实施态读数；门禁 4 契约门为 C-01+C-02 收口后终读（在途判定需 handoff 有基线变动，收口态两件套 + 清单比对为最终口径）；token 门收口复测在 C-02 提交后执行（提交不改内容，读数与实施态一致）。

## 4. 受限与偏差声明

spec §5 预注册四项受限（全部按口径登记）：

1. **R1 flush∥reconcile 并发与多实例姿态同口径**：并行化后 flush 与 reconcile 在单实例也并发——flush 无锁、多实例天然并行，reconcile 的 Redisson 锁只防多实例 reconcile 互斥；窗口内 reconcile SET 可能短暂覆盖 flush INCR 的计数，由下轮对账自愈（10min 内），非新缺陷类别。
2. **R2 兜底为轮询对账非事件驱动**：最长 10min 修复延迟（fixedDelay 10min + initialDelay 2min）；终判同步失败仍抛异常人工感知行为不变（reviewAppeal 零改动）。
3. **R3 二次复核收窄竞态至毫秒级残余、非完全消除**：复核 SELECT 与 ZREM 之间仍存在理论窗口，残余由下一轮结算收敛（10min）。
4. **R4 迁移覆盖 migrate.sh 存量库路径、scratch 库实测留证未做**：本迁移走 scripts/db/migrate.sh 全量重跑路径，幂等逻辑沿既有两先例逐字形态；**scratch MySQL 库实测留证未做（可选受限，SQL 无纯 JVM 单测，验证 = 门禁 + 头注释先例形态背书）**；05 initdb 脚本零触碰（其注释描述的手动路径仍真，新迁移头注释已注明双路径关系）。

实施偏差授权链（条件项）：白名单第 1 项路径 `application.yml` → 实际 `application.properties`（仓内文件头注释实证 snakeyaml 冲突禁改 yml），R1 按实际文件落地、属性键语义逐字等价；此为本批唯一路径级偏差，无其他偏差。

## 5. 双提交记录

- **C-01 实施笔**：`1c35a27`（`1c35a2740749e60564f4482c333d3dbe99f651d1`）：
  `fix(record,verify,leaderboard): 调度池并行化与申诉终判兜底对账及结算ZREM复核和归档列迁移（TASK-253）` —— 仅白名单文件 1-8（8 files changed, 657 insertions(+), 8 deletions(-)）；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-253实施完成与门禁回填（TASK-253）` —— 仅文件 9、10（`spec.md` 回填开工基线占位符为 `c020587` 并改状态「已实施」、`handoff.md` 五节回填）。C-02 为收口最终 HEAD；本笔内容引用本笔哈希属自指，台账不落具体值（`git log --oneline -1` / `git rev-parse HEAD` 即为该笔）；
- **收口日净声明**：`git status --porcelain` 为空（C-02 后实测；`.trae/tmp` 临时件已删，零残留）。
