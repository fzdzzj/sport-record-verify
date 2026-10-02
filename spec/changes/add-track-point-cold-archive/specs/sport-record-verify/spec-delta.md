# 变更增量规格：轨迹点冷热分离归档与终态记录存储治理

## 1. 变更背景与架构依据

- **冷数据定义**：本 delta 以「记录终态 + 完结时长」为冷热边界，而非纯时间戳——轨迹读取链路（listPoints / getRecordWithPoints / pagePoints）全部按 record_id 维度组织且无时间过滤，只有记录状态机能给出「不会再被判定链路触碰」的确定性边界；verify 判定前置为 VERIFYING 活跃态，恒热数据。
- **归档表同分片规则**：track_point_archive 与 track_point 采用相同分片键（user_id）与相同 INLINE 表达式（user_id % 16，独立算法实例），同一记录的热冷数据落同一物理分片编号，迁移为同库跨表操作。
- **幂等优先的正确性设计**：迁移三步（批插归档表 → 删热表 → 置 archived 标志）中标志在事务外置位；任何一步崩溃后，下一轮扫描按「归档表已有则跳过插入、热表残留则补删、两清则直接置标志」自愈。事务注解为辅助，幂等重入为正确性兜底。
- **不变式继承**：轨迹写入路径（submit 批量/逐条）、分片路由必须携带 user_id、sport_record 不分片、状态机乐观锁迁移——全部保持不动。

---

## 2. 需求增量（Delta）

### ADDED Requirement: 轨迹点冷数据归档存储与分片一致性

WHEN 终态且完结超过冷边界天数的记录被归档任务处理，
系统 SHALL 将其全部轨迹点迁移至归档逻辑表 track_point_archive（物理分片表 track_point_archive_0..15），
归档表 SHALL 采用与热表完全一致的分片键（user_id）与分片算法（user_id % 16），
迁移 SHALL 保留轨迹点原始 id 且点数守恒（迁入数等于迁出数），
迁移完成后该记录的 sport_record.archived SHALL 置 1。

#### Scenario: 归档表同分片规则路由
GIVEN 同一 user_id 的轨迹点存在于热表与归档表
WHEN 以携带 (record_id, user_id) 双条件的查询分别访问两逻辑表
THEN 各自路由到相同分片编号的物理表（track_point_N 与 track_point_archive_N，N = user_id % 16）

#### Scenario: 迁移点数守恒且保留原 id
GIVEN 某终态旧记录在热表存有 K 个轨迹点
WHEN 归档任务完成该记录迁移
THEN 归档表中该记录恰有 K 个点、id 与热表原值逐一相同
AND 热表中该记录的点数为 0
AND 该记录 archived 置 1

#### Scenario: 崩溃后重入自愈
GIVEN 归档任务在某记录迁移中途崩溃（批插后未删热 / 删热后未置标志）
WHEN 下一轮归档任务再次扫到该记录（archived 仍为 0）
THEN 按归档表实际状态幂等补作（已插入则不重复插入、热表有残留则补删、两清则仅置标志）
AND 最终收敛到「归档表有点、热表无点、archived = 1」的一致态，不产生重复行

### ADDED Requirement: 归档任务调度、候选边界与批次上限

WHEN 归档定时任务按可配周期（默认 1 小时）执行，
系统 SHALL 以互斥锁（lock:track:archive）保证多实例部署下至多一个实例执行本轮扫描，
候选记录 SHALL 满足全部条件：archived = 0、status 属终态集合（PASSED / REJECTED / RE_PASSED / RE_CONFIRMED）、end_time 非空且早于冷边界（默认 90 天前），
单轮 SHALL 至多处理可配批次上限（默认 20 条记录）且按 id 升序稳定取批，
end_time 为 NULL 的终态记录 SHALL 被保守跳过（不参与归档），
每迁移成功一条记录 SHALL 递增计数器 track.archive.migrated.records。

#### Scenario: 候选扫描谓词完备
GIVEN 存在多类记录：终态 90 天前、终态近期、活跃态（VERIFYING/APPEALING/MANUAL_REVIEW/SUBMITTED）、终态但 end_time 为 NULL、已归档（archived=1）
WHEN 归档任务扫描候选
THEN 仅「终态 + end_time 早于冷边界 + archived = 0 + end_time 非空」的记录入选
AND 扫描结果按 id 升序且不超过批次上限

#### Scenario: 多实例锁互斥
GIVEN 归档任务锁被另一实例持有
WHEN 本实例归档周期触发
THEN 本实例本轮直接跳过（tryLock 不等待、不排队），不产生并发扫描

#### Scenario: 单轮批次上限
GIVEN 符合候选条件的记录数超过批次上限（默认 20）
WHEN 归档任务执行一轮
THEN 本轮至多处理 20 条，其余留待后续轮次（渐进收敛）

#### Scenario: 零轨迹点记录直接置标志
GIVEN 某终态旧记录在热表没有任何轨迹点
WHEN 归档任务处理该记录
THEN 不执行归档插入与热表删除，直接置 archived = 1

### ADDED Requirement: 轨迹查询冷热路由与判定链路恒热

WHEN 用户端以 record_id 读取轨迹（listPoints 全量 / pagePoints 分页），
系统 SHALL 依据该记录的 archived 标志路由：archived = 1 查归档表，否则查热表，
两路径查询 SHALL 同样携带 (record_id, user_id) 双条件单分片路由并按 seq 升序；
verify 判定聚合契约 getRecordWithPoints SHALL 恒查热表（判定前置为活跃态，永不满足归档条件）；
WHEN 对 archived = 1 的记录发起申诉（submitAppeal），
系统 SHALL 以状态无效拒绝（防止复判链路读取已迁走的冷轨迹）。

#### Scenario: 冷记录路由归档表
GIVEN 某 archived = 1 的记录
WHEN 用户端调用 listPoints 或 pagePoints
THEN 轨迹查询落在归档表（携带分片键条件），热表不被访问
AND 返回内容与迁移前该记录的轨迹一致（点与顺序）

#### Scenario: 热记录路径不变
GIVEN 某 archived = 0 或 archived 字段为 NULL 的记录
WHEN 用户端调用 listPoints 或 pagePoints
THEN 查询落在热表，行为与既有实现逐字一致

#### Scenario: 判定聚合契约恒热
GIVEN 任意记录（无论 archived 取值）
WHEN verify 链路调用 getRecordWithPoints
THEN 轨迹查询恒落热表，不路由归档表

#### Scenario: 已归档记录申诉被拒
GIVEN 某 archived = 1 的记录处于可申诉的终态
WHEN 用户端调用 submitAppeal
THEN 返回记录状态无效错误，状态机不发生迁移，不触发复判
