# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（排行榜能力域，全部为新增）。

## ADDED Requirements

### Requirement: 仅通过记录入榜
WHEN 运动记录入榜,
系统 SHALL 仅累积 `status=PASSED` 或 `RE_PASSED` 记录的里程，其他状态 SHALL 不入榜。

#### Scenario: 通过记录入榜
GIVEN 记录经校验或改判进入 PASSED/RE_PASSED
WHEN 榜单刷新
THEN 该记录里程计入用户累计 pass 里程

#### Scenario: 未通过不入榜
GIVEN 记录处于 SUBMITTED/VERIFYING/REJECTED/RE_CONFIRMED
WHEN 榜单刷新
THEN 该记录里程不计入榜单

### Requirement: 事件驱动入榜
WHEN 记录状态迁移触发事件,
系统 SHALL 消费 VERIFIED 事件执行入榜，且 SHALL 以 eventId 去重保证消费幂等。

#### Scenario: VERIFIED 入榜
GIVEN 收到 VERIFIED 事件（recordId、userId、distance）
WHEN 消费者处理
THEN `ZINCRBY leaderboard:overall {distance} {userId}`
AND 写入 leaderboard_contribution（record_id 主键，status=ACTIVE）

#### Scenario: 事件幂等
GIVEN 相同 eventId 重复投递
WHEN 消费者处理
THEN SETNX 去重
AND 入榜与写贡献仅执行一次

### Requirement: 改判回滚
WHEN 已入榜记录被改判驳回,
系统 SHALL 回滚其榜单贡献，且 SHALL 保证回滚幂等。

#### Scenario: 回滚里程
GIVEN 记录 R 已入榜（存在 ACTIVE 贡献）
WHEN 消费到 REJECTED/REVERSED 事件（recordId=R）
THEN `ZINCRBY leaderboard:overall {-distance} {userId}` 回滚
AND contribution status 置 ROLLED_BACK

#### Scenario: 回滚幂等
GIVEN 记录 R 的贡献已 ROLLED_BACK
WHEN 再次消费到回滚事件
THEN 跳过，不重复回滚

#### Scenario: 无贡献不回滚
GIVEN 记录 R 从未入榜（无贡献行）
WHEN 消费到回滚事件
THEN 跳过，不产生负里程

### Requirement: 回滚与入榜并发安全
WHEN 回滚与入榜针对同一记录并发发生,
系统 SHALL 用 Redisson 锁 `lock:rollback:{recordId}` 串行化，避免里程错乱。

#### Scenario: 串行化
GIVEN 记录 R 同时触发入榜与回滚
WHEN 两者竞争锁
THEN 依序执行，最终榜单与 contribution 状态一致

### Requirement: 总榜查询
WHEN 客户端查询总榜,
系统 SHALL 按累计 pass 里程降序返回前 N 名，含排名、用户、里程。

#### Scenario: 查询成功
GIVEN 榜单 ZSet 存在成员
WHEN 请求 `GET /api/leaderboard?type=overall`
THEN 返回按里程降序的榜单
AND 每项含 rank/userId/nickname/distance

### Requirement: 好友榜查询
WHEN 客户端查询好友榜,
系统 SHALL 经 Feign 获取好友列表，ZSet 结果 SHALL 按好友过滤，只显示好友。

#### Scenario: 只显示好友
GIVEN 用户 A 有好友 B、C，非好友 D
WHEN 请求 `GET /api/leaderboard?type=friend`
THEN 结果仅含 B、C（若其有 pass 里程）
AND 排除 D

#### Scenario: 无好友或未上榜
GIVEN 用户无好友，或好友均无 pass 里程
WHEN 请求好友榜
THEN 返回空榜或友好提示

### Requirement: 快照结算防重
WHEN 定时结算任务触发,
系统 SHALL 用 Redisson 锁 `lock:scheduler:leaderboard` 保证多实例仅一个执行，并 SHALL 以 contribution 汇总为准纠偏 ZSet。

#### Scenario: 多实例仅一个执行
GIVEN 多个服务实例同时触发结算
WHEN 竞争锁
THEN 仅一个实例执行结算
AND 其他实例跳过

#### Scenario: 对账纠偏
GIVEN ZSet 与 contribution 汇总漂移
WHEN 结算任务执行
THEN 以 contribution ACTIVE 汇总为准纠正 ZSet
AND 标记 settled_at

---

## 备注

- 榜单是校验闭环的收口，依赖 add-verify-engine（事件）、add-friend-module（好友列表 Feign）；落地顺序在其后。
- ZSet `leaderboard:overall`（member=userId，score=累计 pass 里程）为热读层；leaderboard_contribution 行为权威源与回滚锚点（弹药：Redis ZSet + 事件驱动最终一致 + 定时防重）。
- 事件 Tag 与表结构以审批版 §4.5/§6.2/§7.1/§7.5 为唯一依据。