# spec-delta：wire-verify-outbox

## ADDED Requirements

### Requirement: 判定事件可靠投递

WHEN 判定结果落库,
系统 SHALL 同事务写入事件待发行行（verify_db.verify_event_outbox，status=PENDING），事件 SHALL 由 relay 唯一投递；
系统 SHALL NOT 在判定路径上同步直发事件，也 SHALL NOT 以吞异常的方式放弃已产生的判定事件。

#### Scenario: 判定结果与待发行同生共死

GIVEN 判定完成（PASSED/REJECTED）或终判完成（RE_PASSED/RE_CONFIRMED）
WHEN 判定结果行写库
THEN 同事务写入 outbox PENDING 行（含写入时生成的 eventId、topic、tag、payload）
AND 任一步失败整体回滚，不出现「判定已落库但事件行不存在」的状态

#### Scenario: relay 是唯一出口

GIVEN 判定主链路已落库并提交
WHEN 事件投递
THEN 仅由 relay 定时扫描 PENDING 行投递
AND 判定线程不调用同步发送（无「直发 + relay」双发路径）

#### Scenario: 延迟上界为 relay 周期

GIVEN 判定结果已提交且 outbox 存在 PENDING 行
WHEN relay 按周期（默认 5s）扫描
THEN 事件在周期内投递
AND 榜单结算的定时纠偏仍是最终一致的兜底

#### Scenario: 表结构随脚本交付

GIVEN 按 `sql/03-verify-db.sql` 初始化 verify_db
WHEN 查询 verify_event_outbox
THEN 表存在且含 event_id 唯一键与 status/retry_count/created_at/sent_at 列
AND 脚本可重复执行（IF NOT EXISTS）

#### Scenario: 投递失败保留行

GIVEN relay 投递某行失败
WHEN 处理该行
THEN retry_count+1 且 status 保持 PENDING 留下轮重试
AND 超过阈值（默认 16）仅记录告警并保留行供人工处理，SHALL NOT 静默丢弃

---

## MODIFIED Requirements

### Requirement: 校验事件与幂等

WHEN 校验流程产生状态变化,
系统 SHALL 经 RocketMQ 发布 SUBMITTED/VERIFIED/REJECTED 事件，并 SHALL 以 eventId 去重保证消费幂等。
判定/终判事件（VERIFIED/REJECTED）SHALL NOT 在判定线程同步直发：SHALL 与结果行同事务写入本地消息表后由 relay 唯一投递（见「判定事件可靠投递」）；
eventId SHALL 在 outbox 写入时生成并保存，relay 重发 SHALL 沿用行内 eventId，使消费端幂等键在重试间稳定。

#### Scenario: 触发校验事件

GIVEN 记录提交进入 VERIFYING
WHEN 状态迁移完成
THEN 发布 SUBMITTED 事件（Tag 区分）
AND verify 消费后拉轨迹执行判定并回调

#### Scenario: 判定事件经待发行表投递

GIVEN 判定或终判产生 VERIFIED/REJECTED 事件
WHEN 事件投递完成
THEN 该事件先以 PENDING 行落 verify_event_outbox、再由 relay 按行内 topic/tag 投递并标 SENT
AND 消费端读到的 eventId 与行内 eventId 逐字一致

#### Scenario: 事件幂等消费

GIVEN 相同 eventId 的事件重复投递
WHEN 消费者处理
THEN SETNX 去重
AND 业务仅执行一次

#### Scenario: 失败进死信

GIVEN 消费失败达到重试阈值
WHEN 消费者无法处理
THEN 消息进入 record-verify-events-dlq
AND 可人工排查
