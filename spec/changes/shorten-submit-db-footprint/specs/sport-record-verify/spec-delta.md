# spec-delta：shorten-submit-db-footprint

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更。范围仅限提交路径的已提交状态、提交事务 SQL 次数，以及分片 SQL 展示默认关闭。不修改校验状态机的合法状态集合，不修改幂等键，不修改连接池默认值，不修改 innodb 刷盘语义。

## ADDED Requirements

### Requirement: 提交事务不落不可见中间态
WHEN 客户端首次提交含轨迹点的新运动记录且本地事务成功提交,
系统 SHALL 使已提交的 `sport_record.status` 为 VERIFYING,
SHALL NOT 把仅存在于同一事务内部、对其他连接不可见的 SUBMITTED 再单独 UPDATE 一次。
系统 SHALL 仍在事务提交之后异步发布 SUBMITTED 事件。
系统 SHALL 保留对已存在 SUBMITTED 行执行 SUBMITTED 到 VERIFYING 的乐观锁回调能力。

#### Scenario: 首次提交事务提交后即为 VERIFYING
GIVEN 客户端携带新 request_id 提交含轨迹点的记录
WHEN 本地事务成功提交
THEN 库中该行 status 为 VERIFYING
AND 提交响应 status 为 VERIFYING
AND 事务提交后发布 SUBMITTED 事件
AND 提交路径没有一次 SUBMITTED 到 VERIFYING 的 UPDATE

#### Scenario: 轨迹写入失败不发事件
GIVEN 首次提交过程中轨迹点写入抛错
WHEN 本地事务回滚
THEN 不发布 SUBMITTED 事件
AND 不留下半截 sport_record 行

#### Scenario: 历史 SUBMITTED 行仍可迁到 VERIFYING
GIVEN 库中已存在 status=SUBMITTED 的记录
WHEN 调用既有状态回调请求迁到 VERIFYING 且 version 匹配
THEN 系统以乐观锁 UPDATE 将该行迁到 VERIFYING
AND 不因为提交路径不再写 SUBMITTED 而拒绝该回调

### Requirement: 分片 SQL 展示默认关闭
WHEN record-service 以默认配置处理提交路径的轨迹写入,
系统 SHALL NOT 在热路径上打印 ShardingSphere 逻辑 SQL 与实际 SQL。
系统 MAY 通过环境变量 `SS_SQL_SHOW=true` 临时打开 SQL 展示，且该开关默认必须为 false。

#### Scenario: 默认关闭
GIVEN 未设置 SS_SQL_SHOW
WHEN 服务加载主 `sharding.yaml`
THEN sql-show 解析为 false

#### Scenario: 显式打开仅用于排查
GIVEN 环境变量 SS_SQL_SHOW=true
WHEN 服务加载主 `sharding.yaml`
THEN sql-show 解析为 true
AND 不得把 true 写进仓库默认值

### Requirement: 本次数据库类改动用同一负载验收
WHEN 实施缩短提交 DB 足迹的改动,
系统 SHALL 只修改数据库一类因素,
并 SHALL 用与 TASK-138 相同的 100 并发 x 2000 请求、相同样本复测。
系统 SHALL NOT 在同一次变更中修改连接池默认值、JVM 参数、索引或 innodb 刷盘。

#### Scenario: 复测对比 TASK-138
GIVEN TASK-138 已记录 QPS 120.90 与 P50 777.55ms
WHEN 完成允许清单内的提交路径改动
THEN 使用 `bash scripts/perf/run-perf.sh load 100 2000 dbfoot` 复测
AND 报告必须写出新旧数字
AND 不得把旧环境 137 QPS 写成当前结果

#### Scenario: 指标没有改善就停止叠加
GIVEN 已经关掉 sql-show 默认值并去掉提交路径那条不可见 UPDATE
WHEN 同一负载的 QPS 与 P50 相对 TASK-138 没有改善
THEN 停止继续修改连接池、JVM、索引或刷盘参数
AND 在报告中写明剩余假设（例如 commit fsync 地板）

## MODIFIED Requirements

### Requirement: 轨迹提交幂等
**Previous**：首次提交成功时「记录落库，状态置 SUBMITTED，并进入校验流程」。实现上曾在同一事务内 INSERT SUBMITTED 再 UPDATE 为 VERIFYING；该 SUBMITTED 从未被其他连接看见，提交 API 响应已是 VERIFYING。

WHEN 客户端提交运动记录,
系统 SHALL 以 `request_id` 唯一识别，重复提交 SHALL 返回原结果而非重复入库。
首次提交且本地事务成功时，系统 SHALL 将已提交状态置为 VERIFYING 并进入校验流程。

#### Scenario: 首次提交成功
GIVEN 客户端携带新 request_id
WHEN 提交记录且本地事务成功
THEN 记录落库，已提交状态为 VERIFYING
AND 进入校验流程
AND 事务提交后发布 SUBMITTED 事件

#### Scenario: 重复提交幂等
GIVEN 相同 request_id 已提交过
WHEN 再次提交相同 request_id
THEN 系统返回 3004 幂等冲突或原结果
AND 不产生第二条记录