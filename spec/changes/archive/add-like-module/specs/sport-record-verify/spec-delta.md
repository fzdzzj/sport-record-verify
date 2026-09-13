# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（点赞能力域，全部为新增）。

## ADDED Requirements

### Requirement: 点赞前置校验
WHEN 用户对运动记录点赞,
系统 SHALL 校验记录 `status=PASSED`，未通过校验的记录 SHALL 返回 6001 且不产生点赞。

#### Scenario: 通过校验可赞
GIVEN 记录 status=PASSED
WHEN 用户提交点赞
THEN 点赞成功
AND 计数 +1

#### Scenario: 未通过校验被拒
GIVEN 记录 status=REJECTED/VERIFYING/SUBMITTED
WHEN 用户提交点赞
THEN 返回 6001（记录未通过校验不可点赞）
AND 不产生点赞与计数变化

### Requirement: 点赞幂等
WHEN 同一用户对同一记录重复点赞,
系统 SHALL 保证只计数一次，且 SHALL 落库仅一条 `(record_id,user_id)`。

#### Scenario: 重复点赞只计一次
GIVEN 用户 A 已对记录 R 点赞
WHEN 用户 A 再次点赞记录 R
THEN 计数保持不变（+1 仅发生一次）
AND record_like 落库仅一条 `(record_id,user_id)`

#### Scenario: 联合主键防重
GIVEN record_like 已存在 (record_id,user_id) 行
WHEN 异步落库尝试再次 INSERT 相同键
THEN 联合主键冲突被跳过
AND 不产生重复行

### Requirement: 计数读热写冷
WHEN 点赞/取消发生,
系统 SHALL 用 Redis `INCR`/`DECR` 维护计数，读取 SHALL 优先走 Redis，缺失时 SHALL 兜底 DB `COUNT(*)` 并回填。

#### Scenario: 计数走 Redis
GIVEN 点赞发生
WHEN 更新计数
THEN `like:count:{recordId}` 原子 INCR
AND 查询点赞数优先读该 Redis 值

#### Scenario: 兜底回填
GIVEN Redis 计数键缺失
WHEN 查询点赞数
THEN 兜底 DB COUNT(*) 得到真实值
AND 回填 Redis 计数键

### Requirement: 异步批量落库
WHEN 点赞/取消产生,
系统 SHALL 先记录 pending 操作，由定时任务 SHALL 批量持久化到 record_like，且 SHALL 用 Redisson 锁保证多实例仅一个执行。

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

### Requirement: 取消点赞
WHEN 用户取消点赞,
系统 SHALL 使计数 -1 并 SHALL 异步删除对应 `(record_id,user_id)` 行，重复取消 SHALL 幂等。

#### Scenario: 取消成功
GIVEN 用户 A 已点赞记录 R
WHEN 用户 A 取消点赞
THEN 计数 -1
AND 异步删除 record_like 对应行

#### Scenario: 重复取消幂等
GIVEN 用户 A 未点赞或已取消
WHEN 用户 A 再次取消
THEN 计数不再变化（下限 0）
AND 无对应行可删

### Requirement: 最终一致
WHEN 计数与落库发生,
系统 SHALL 保证两者最终一致，并 SHALL 提供对账兜底以 DB 行为准纠偏。

#### Scenario: flush 后一致
GIVEN 点赞与取消操作已 flush
WHEN 对比 Redis 计数与 record_like 行数
THEN 两者一致

#### Scenario: 对账纠偏
GIVEN 因进程重启导致计数漂移
WHEN 对账任务执行
THEN 以 DB record_like 行为准纠正 Redis 计数

---

## 备注

- 点赞只对 PASSED 记录开放，与校验引擎（add-verify-engine）的状态机强耦合，落地顺序在前者之后。
- 计数权威源为 record_like 行，Redis 为读热写冷的加速层（Redis 原子计数 + 最终一致 + 异步批量）。
- 错误码 6001 与 record_like 表结构以审批版 §4.6/§4.8/§6.2 为唯一依据。