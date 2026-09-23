# spec-delta：adopt-native-mq-retry

> 合并口径提示（归档时读）：本 delta 的 MODIFIED 目标态**已包含在途变更
> `wire-verify-outbox` 对同一需求（「校验事件与幂等」）的修改**——其 SHALL 行新增的
> 「判定事件由 relay 唯一投递 / eventId 写入时生成」一段与 `判定事件经待发行表投递` 场景。
> 两个变更先后归档都可推出同一终态（本 delta 按目标态整段书写，不与之相互覆盖），
> 但**建议先归档 `wire-verify-outbox`**，避免中间态里出现引用尚不存在需求的场景。

## MODIFIED Requirements

### Requirement: 校验事件与幂等

WHEN 校验流程产生状态变化,
系统 SHALL 经 RocketMQ 发布 SUBMITTED/VERIFIED/REJECTED 事件，并 SHALL 以 eventId 去重保证消费幂等。
判定/终判事件（VERIFIED/REJECTED）SHALL NOT 在判定线程同步直发：SHALL 与结果行同事务写入本地消息表后由 relay 唯一投递（见「判定事件可靠投递」）；
eventId SHALL 在 outbox 写入时生成并保存，relay 重发 SHALL 沿用行内 eventId，使消费端幂等键在重试间稳定。
消费失败的重投与超次入死信 SHALL 由 RocketMQ 原生重试承载：消费者 SHALL 以客户端参数 `maxReconsumeTimes=3` 声明重试上限，
超次消息 SHALL 进入该消费组的内建死信队列 `%DLQ%<consumerGroup>`；
业务代码 SHALL NOT 自建重试计数键，也 SHALL NOT 向自建死信 topic 投递。

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

#### Scenario: 重试上限走 MQ 原生

GIVEN 消费者以 maxReconsumeTimes=3 启动
WHEN 消费失败返回 RECONSUME_LATER
THEN 由 broker 按退避重投，连同首次共最多消费 4 次
AND 业务代码不写入、不读取任何自建重试计数键

#### Scenario: 失败进死信

GIVEN 消费失败达到重试上限（broker 侧 reconsumeTimes >= 3）
WHEN 消费者无法处理
THEN 消息进入该消费组的内建死信队列 `%DLQ%<consumerGroup>`
AND 消息不再投递到自建死信 topic record-verify-events-dlq
AND 可人工排查
