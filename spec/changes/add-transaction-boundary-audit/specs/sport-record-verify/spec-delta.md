# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（写路径事务边界）。

## ADDED Requirements

### Requirement: 写路径事务边界

WHEN 一条业务操作需要写入多行或多表且中间失败必须全部回滚,
系统 SHALL 在同一本地事务中提交或回滚这些数据库写。
单行写入且有唯一键或乐观锁幂等时，系统 SHALL NOT 仅为形式增加本地事务。

#### Scenario: 提交记录与轨迹同进退

GIVEN 客户端提交一条含轨迹点的新记录
WHEN 轨迹点写入失败
THEN 不保留已插入的主记录
AND 不发出校验事件

#### Scenario: 好友接受两写同进退

GIVEN 待接受的好友申请
WHEN 写入好友关系失败
THEN 申请状态不停留在已接受
AND 不出现申请已接受但无好友行

#### Scenario: 单行注册不加形式事务

GIVEN 用户注册只插入一行 user（角色在同一行默认 USER）
WHEN 注册成功或因手机号唯一键失败
THEN 不要求额外的本地事务来保证角色初始化
AND 不创建第二张角色表作为本需求的一部分

### Requirement: 跨存储与跨服务写不纳入本地事务

WHEN 业务写同时涉及数据库与 Redis，或涉及数据库与跨服务调用,
系统 SHALL 使用最终一致、幂等与对账或补偿，SHALL NOT 用本地事务假装这些资源一起原子提交。

#### Scenario: 点赞热路径不进本地事务

GIVEN 用户点赞
WHEN 系统更新 Redis 计数并投递 pending
THEN 不开启覆盖 Redis 与 DB 的本地事务
AND 最终一致由后续 flush 与对账保证

#### Scenario: 入榜不把排行集合纳入本地事务

GIVEN 校验通过事件触发入榜
WHEN 贡献表写入后更新排行集合
THEN 不以本地事务包裹排行集合
AND 允许由结算任务以贡献表为准纠偏

#### Scenario: 校验主路径不把远程调用包进本地事务

GIVEN 校验引擎需要拉取轨迹并回调记录状态
WHEN 执行判定
THEN 本地事务（若有）不包含远程调用或消息发送
AND 不允许因此引入分布式事务框架

## MODIFIED Requirements

### Requirement: 异步批量落库

**Previous**：定时任务批量把 pending 持久化到 record_like，用锁保证多实例只跑一个；未要求同一批插入与删除同进退，也未要求必须在本地事务成功之后才清理队列。

WHEN 点赞/取消产生,
系统 SHALL 先记录 pending 操作，由定时任务 SHALL 批量持久化到 record_like，且 SHALL 用 Redisson 锁保证多实例仅一个执行。
同一批 flush 中的插入与删除 SHALL 在同一本地事务中提交或回滚；清理 pending 队列 SHALL 仅在该本地事务成功之后发生。

#### Scenario: 批量落库

GIVEN 存在待 flush 的点赞/取消操作
WHEN 定时任务触发
THEN 批量写 record_like（点赞 INSERT、取消 DELETE）
AND pending 操作被清理

#### Scenario: 多实例防重

GIVEN 多个服务实例同时触发 flush
WHEN 竞争 `lock:like:flush` 锁
THEN 仅一个实例执行 flush
AND 其他实例跳过

#### Scenario: 同一批插入与删除同进退

GIVEN 同一批 pending 既有点赞也有取消
WHEN 删除写入失败
THEN 该批插入亦不保留
AND pending 队列不被清理以便重试

---

## 备注

- 已有本地事务：提交、好友 accept、规则 activate。本变更不是注解铺满。
- 申诉终判后远程回调失败属跨服务残余，不在本变更用本地事务或 Seata 解决。
