# spec-delta：add-verify-degrade-status-index

## ADDED Requirements

### Requirement: 滞留校验扫描有状态时间索引

WHEN 系统扫描滞留在 VERIFYING 且创建时间早于阈值的运动记录,
系统 SHALL 能使用 `sport_record` 上 `(status, created_at)` 的二级索引。

#### Scenario: 新库包含复合索引

GIVEN 使用仓库初始化脚本创建 record_db
WHEN 查看 sport_record 的索引
THEN 存在 `(status, created_at)` 二级索引
AND 原有主键、幂等唯一键和 `(user_id, created_at)` 索引仍在

#### Scenario: 同一查询前后执行计划可对比

GIVEN scratch 库中有同一批运动记录
WHEN 对滞留扫描 SQL 记录加索引前后的 EXPLAIN
THEN 两次使用同一 SQL、同一数据和同一数据库版本
AND 加索引后的计划显示使用该状态时间索引

### Requirement: 索引优化不改变降级语义

WHEN 增加滞留扫描索引,
系统 SHALL NOT 改变滞留阈值、扫描上限、状态迁移、补偿或转人工语义。

#### Scenario: 查询语义保持不变

GIVEN 同一批 VERIFYING 和 MANUAL_REVIEW 记录
WHEN 使用新索引执行滞留扫描
THEN 返回的记录集合与加索引前的查询条件结果一致
AND 不因索引改变记录状态
