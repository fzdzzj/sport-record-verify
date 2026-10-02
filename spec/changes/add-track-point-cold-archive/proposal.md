# 变更提案：轨迹点冷热分离归档与终态记录存储治理（add-track-point-cold-archive）

## 1. Why

`track_point` 按 `user_id % 16` 分片为 16 张物理表（`record-service/src/main/resources/sharding.yaml:49-62`，建表 `sql/04-track-point-shards.sql`），数据量随用户运动记录持续增长，而**全仓不存在任何针对 track_point 或 sport_record 旧数据的清理/归档/TTL 逻辑**（已逐仓核实）。由此产生：

1. **冷数据驻留高频在线分片**：已终态完结（判定/复判闭环）的历史记录轨迹点永久停留在在线分片中，在线分片体积单调增长，索引维护、备份与查询扫描成本随历史积累持续上升（课题路线图课题 3 原文痛点）；
2. **查询无时间维度可收敛**：轨迹读取链路（`listPoints` / `getRecordWithPoints` / `pagePoints`，`record-service/src/main/java/com/sportverify/record/service/SportRecordService.java:285-337`）全部按 `record_id` 维度组织，无任何按时间过滤的查询——无法靠查询侧时间条件下推缓解，必须做存储侧分离；
3. **冷热边界天然可用**：`sport_record.status` 状态机（`api/src/main/java/com/sportverify/api/record/RecordStatus.java`，8 态）区分活跃态（SUBMITTED/VERIFYING/APPEALING/MANUAL_REVIEW）与终态（PASSED/REJECTED/RE_PASSED/RE_CONFIRMED）；verify 判定链路（`getRecordWithPoints`）只发生在记录提交后的短窗口内（VERIFYING 前置），**永远只碰热数据**——终态且完结已久的记录轨迹是明确的冷数据。

本提案以「记录终态 + 完结时长」为冷热边界，把冷记录的轨迹点从在线分片迁入同分片规则的归档表，用户端读旧记录按归档标志路由，verify 判定链路零改动。

---

## 2. What Changes

### 2.1 归档存储基建（同分片规则归档表）

- 新增逻辑表 `track_point_archive`，物理表 `track_point_archive_0..15`，**分片键与算法与热表完全一致**（`user_id % 16`，INLINE 独立算法实例），DDL 逐字段同 `track_point_N`（`sql/05-track-point-archive-shards.sql`，幂等 `CREATE TABLE IF NOT EXISTS`，沿用 docker-entrypoint-initdb.d 文件名序执行惯例）；
- `sport_record` 增加 `archived` 标志列（`TINYINT NOT NULL DEFAULT 0`）与扫描索引 `idx_archive (archived, end_time)`（既有库手动执行一次 ALTER，与仓内 SQL 脚本纯手动执行惯例一致）；
- 新增实体 `TrackPointArchive` 与 `TrackPointArchiveMapper`（含多值批插 `insertBatch`，照 `TrackPointMapper.insertBatch` 先例，**迁移保留原雪花 id**，不重新生成）。

### 2.2 归档任务（调度、互斥与幂等）

新增 `TrackPointArchiveService`（record-service）：

- `@Scheduled(fixedDelayString = "${track.archive.interval-ms:3600000}")` 默认 1h 一轮，Redisson 锁 `lock:track:archive` 互斥（`tryLock` 不等待，未获锁即跳过本轮，多实例至多一个执行者——与 `lock:like:flush` / `lock:like:reconcile` 同款模式）；
- **候选扫描**：`archived = 0 AND status IN 终态集合 AND end_time IS NOT NULL AND end_time < NOW() − coldDays`（默认 90 天，可配 `track.archive.cold-days`），`ORDER BY id LIMIT batch`（默认 20 条/轮，`track.archive.batch-records`），单轮小批避免长事务；
- **逐记录迁移**（幂等三步）：① 热表读该记录全部点；② 归档表批插（若归档表已有该记录数据则跳过插入——崩溃重入自愈）；③ 删热表该记录点；事务外置 `archived = 1` 标志（`UPDATE ... WHERE id = ? AND archived = 0`）。`@Transactional` 覆盖插入+删除（同库 ds0 本地事务），**即便事务注解不生效，三步幂等设计保证崩溃后重入自愈**（正确性以幂等为主、事务为辅的双保险）；
- **显式跳过**：`end_time` 为 NULL 的终态记录不参与归档（保守不迁移）；0 轨迹点记录直接置标志；
- 迁移成功递增 Micrometer Counter `track.archive.migrated.records`（构造注册，与课题 2 可观测口径一致）。

### 2.3 读路径冷热路由（用户端回退，verify 恒热）

- `listPoints` / `pagePoints`：按 `sport_record.archived` 标志路由——`archived = 1` 查归档表（同样携带 `(record_id, user_id)` 双条件单分片路由），否则查热表，既有热路径行为逐字不变；
- `getRecordWithPoints`（verify 判定链路）**零改动、恒查热表**：判定前置为 VERIFYING 活跃态，永不满足归档条件；补偿链路（`compensateStuckVerifying` 扫 VERIFYING/MANUAL_REVIEW）同理不碰冷数据；
- **申诉防线**：`submitAppeal` 对 `archived = 1` 的记录直接拒绝（`RECORD_STATUS_INVALID`）——防止终态 90 天后申诉导致复判链路读空（复判会拉轨迹点，而冷数据已迁走）。

### 2.4 显式不做（范围红线）

- **不改分片算法/分片数**（16 片、`user_id % 16` 不动；归档表是新增逻辑表，不动 track_point 既有规则）；
- **不迁移既有历史数据**：归档表初始为空，由归档任务按批渐进迁移，不做一次性全量搬移脚本；
- **不删 sport_record 主表行**（记录元数据永留在线库，仅轨迹点冷热分离）；
- **不做冷热透明 UNION 视图 / 读路径双查兜底**：按标志路由，接受「迁移完成至置标志之间」的毫秒级窗口内读空（该窗口仅影响终态 90 天以上旧记录，读概率极低；登记为已知边界）；
- **不引入 Flyway / Testcontainers / 新中间件 / 新 Maven 插件**（历史两度否决）；
- **不动 verify-service / api 模块任何文件**（无跨模块契约变化）；
- **不宣称任何存储/查询性能收益**（未实测；真实 MySQL 下迁移行为不在本轮单测覆盖内）。

---

## 3. Impact

- **改动面**：仅 `record-service` 单模块（4 新建 + 3 修改 + 1 SQL 脚本 + 1 分片配置），无跨模块契约变化，无新依赖（Redisson/Micrometer/MyBatis-Plus 均已有）；
- **写路径行为**：轨迹写入零变化（热表写入路径不动）；新增归档任务为渐进式后台搬运；
- **读路径行为**：热记录零变化；冷记录（archived=1）改读归档表；verify 链路零变化；
- **运维**：`sql/05` 需在既有库手动执行一次（新库经 docker-entrypoint-initdb.d 自动执行）；归档周期/冷边界/批大小均可经环境变量覆盖；
- **测试预算**：`record-service` 115 → ≥127（新建 TrackPointArchiveServiceTest +8，SportRecordServiceTest +4），全仓 438 → ≥450，既有用例零翻转（既有 mock 的 record 对象 `archived` 字段为 null，路由判定天然走热路径，预期零适配或仅构造器适配）。

---

## 4. 判定与停止条件

1. 若归档表分片规则与热表不一致（分片键/算法/物理表数任一不等）⇒ **停止**。
2. 若迁移不幂等（同一记录重复归档产生重复行，或崩溃重入后无法自愈到 `archived = 1` 终态）⇒ **停止**。
3. 若非终态记录（含 APPEALING/MANUAL_REVIEW 活跃态）被纳入归档扫描 ⇒ **停止**。
4. 若读路径路由破坏既有热记录查询行为（archived=0/null 走了归档表）⇒ **停止**。
5. 若 `getRecordWithPoints` 被改动为读归档表（verify 链路语义变化）⇒ **停止**。
6. 若破坏已有单测或全模块测试失败、Checkstyle 超 862 基线 ⇒ **停止**。
7. 若实现顺手加入一次性全量迁移、UNION 视图、分片调参、verify 链路改动等未授权变更 ⇒ **停止**。
