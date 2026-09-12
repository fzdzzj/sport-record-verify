# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（好友能力域，全部为新增）。

## ADDED Requirements

### Requirement: 好友申请创建
WHEN 用户向另一用户发起好友申请,
系统 SHALL 创建 `status=PENDING` 的 friend_request 并返回申请单。

#### Scenario: 申请成功
GIVEN 用户 A(id=1001) 向用户 B(id=1002) 发起申请
AND A、B 之间无任何 PENDING 申请或既有关系
WHEN 提交 `POST /api/friends/requests {targetUserId:1002}`
THEN 创建 PENDING 申请单
AND 返回 `{id, fromUser:1001, toUser:1002, status:"PENDING"}`

#### Scenario: 目标用户不存在
GIVEN 目标用户 id 不存在
WHEN 提交好友申请
THEN 返回 2002（用户不存在）
AND 不创建申请单

### Requirement: 申请幂等去重
WHEN 用户发起好友申请,
系统 SHALL 识别同向重复申请、反向 PENDING 申请、既有关系，并 SHALL 返回 5001 或原申请单而非重复建单。

#### Scenario: 同向重复申请
GIVEN A→B 已存在 PENDING 申请
WHEN A 再次向 B 发起申请
THEN 返回 5001 或原申请单
AND 不产生第二条 PENDING 单

#### Scenario: 反向 PENDING 已存在
GIVEN B→A 已存在 PENDING 申请
WHEN A 向 B 发起申请
THEN 返回 5001（重复申请或已存在关系）

#### Scenario: 已存在关系
GIVEN A、B 已是好友（friendship 存在）
WHEN 任一方再次发起申请
THEN 返回 5001
AND 不创建申请单

### Requirement: 申请状态机
WHEN 好友申请发生流转,
系统 SHALL 遵循 PENDING→ACCEPTED/REJECTED/CANCELLED，且 SHALL 仅允许 PENDING 状态的申请流转。

#### Scenario: 同意申请
GIVEN 申请处于 PENDING
WHEN 目标用户执行 accept
THEN 申请状态迁至 ACCEPTED
AND 写入 friendship 关系

#### Scenario: 拒绝申请
GIVEN 申请处于 PENDING
WHEN 目标用户执行 reject
THEN 申请状态迁至 REJECTED
AND 不写入 friendship

#### Scenario: 非 PENDING 流转被拒
GIVEN 申请已处于 ACCEPTED/REJECTED
WHEN 再次执行 accept/reject
THEN 流转失败
AND 返回 5002（关系不存在）或业务异常

### Requirement: 好友关系规范化存储
WHEN 好友关系落库,
系统 SHALL 以 `(user_low, user_high)` 存储且强制 `user_low < user_high`，主键唯一 + CHECK 约束 SHALL 从根上消除 A-B/B-A 重复行。

#### Scenario: 关系归一化
GIVEN 用户 1002 与 1001 建立好友关系
WHEN 写入 friendship
THEN 存储为 `(user_low=1001, user_high=1002)`
AND 不出现 `(1002,1001)` 逆序行

#### Scenario: 逆序重复被拦截
GIVEN 已存在 `(1001,1002)` 关系行
WHEN 尝试写入 `(1002,1001)`
THEN 主键/CHECK 约束拒绝
AND 不产生重复关系

### Requirement: 并发互加唯一性
WHEN 两个用户并发互发申请并最终建立关系,
系统 SHALL 用 Redisson 可重入锁 `lock:friend:{low}_{high}` 串行化「检查-建单」，配合规范化存储，保证 SHALL 只产生一条 friendship。

#### Scenario: 并发互加只产生一条关系
GIVEN 用户 A 与 B 同时互发申请
WHEN 双方申请与同意并发执行
THEN 两个请求竞争同一把锁 `lock:friend:{min}_{max}`
AND 最终 friendship 仅一条

#### Scenario: 锁键归一一致
GIVEN A 发起 A→B 申请，B 发起 B→A 申请
WHEN 分别计算锁键
THEN 两者得到相同锁键 `lock:friend:{low}_{high}`（min/max）
AND 保证串行化

### Requirement: 好友列表
WHEN 用户查询好友列表,
系统 SHALL 分页返回且 SHALL 仅包含 ACCEPTED 状态的好友。

#### Scenario: 仅返回已接受好友
GIVEN 用户 A 有 ACCEPTED、PENDING、REJECTED 三种关系的申请
WHEN A 查询 `GET /api/friends?page=1&size=10`
THEN 仅返回 ACCEPTED 关系对应的好友
AND 排除 PENDING/REJECTED/CANCELLED

#### Scenario: 分页正确
GIVEN 用户好友数超过单页大小
WHEN 分页查询
THEN 返回当前页数据
AND 含正确的总数/分页元信息

---

## 备注

- 双向语义：关系一旦 ACCEPTED，双方互见；存储层以 `(user_low,user_high)` 单向归一化承载双向关系。
- 好友榜（record-service 经 UserApi 过滤）属后续排行榜变更，本提案只交付 user-service 侧契约与能力。
- 状态机字段值与错误码（5001/5002/2002）以审批版 §4.1/§6.1/§4.8 为唯一依据。