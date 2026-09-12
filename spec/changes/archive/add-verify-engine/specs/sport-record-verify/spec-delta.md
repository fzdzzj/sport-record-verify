# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（校验引擎能力域，全部为新增）。

## ADDED Requirements

### Requirement: 轨迹分片存储
WHEN 一条运动记录的轨迹点被写入,
系统 SHALL 按 `user_id % 16` 路由到对应分片表 `track_point_0..15`，并 SHALL 先经 `sport_record → user_id` 解析分片键。

#### Scenario: 分片路由正确
GIVEN 用户 user_id=100（100%16=4）
WHEN 该用户提交轨迹点
THEN 轨迹点写入 `track_point_4`
AND 不落其他分片

#### Scenario: 记录本身不分片
GIVEN sport_record 未声明分片
WHEN 写入运动记录主表
THEN 数据落单表
AND 查询轨迹前先查 sport_record 得到 user_id 再路由

#### Scenario: 分片分页查询
GIVEN 轨迹点分布在多个分片
WHEN MyBatis-Plus 分页查询该用户轨迹
THEN 分页插件正确绑定 ShardingSphere 代理数据源
AND 返回跨分片合并的完整分页结果

### Requirement: 轨迹提交幂等
WHEN 客户端提交运动记录,
系统 SHALL 以 `request_id` 唯一识别，重复提交 SHALL 返回原结果而非重复入库。

#### Scenario: 首次提交成功
GIVEN 客户端携带新 request_id
WHEN 提交记录
THEN 记录落库，状态置 SUBMITTED
AND 进入校验流程

#### Scenario: 重复提交幂等
GIVEN 相同 request_id 已提交过
WHEN 再次提交相同 request_id
THEN 系统返回 3004 幂等冲突或原结果
AND 不产生第二条记录

### Requirement: 漂移预处理
WHEN 校验引擎处理轨迹点数组,
系统 SHALL 逐点计算瞬时速度，将 `v > V_DRIFT(默认20 m/s)` 或 `Δt < 0.1s` 的点标记为漂移并剔除，且 SHALL 保留原始数组用于审计。

#### Scenario: 漂移点剔除
GIVEN 轨迹含瞬时速度 25 m/s 的跳变点
WHEN 预处理器运行
THEN 该点被标记为漂移
AND 从有效点集剔除
AND 原始数组保留待审计

#### Scenario: 高漂移比例记软证据
GIVEN 漂移点占比 driftRatio > 30%
WHEN 预处理器汇总
THEN 记录软证据 PREPROCESS_SUSPICIOUS
AND 写入 preprocess 统计

#### Scenario: 低漂移不触发
GIVEN 漂移点占比 ≤ 30%
WHEN 预处理器汇总
THEN 不产生 PREPROCESS_SUSPICIOUS 软证据

### Requirement: 规则链判定（R1-R4）
WHEN 校验引擎执行规则链,
系统 SHALL 按序计算 R1 速度、R2 加速度、R3 停留、R4 距离一致性，并 SHALL 为每条命中规则记录级别（HARD/SOFT）与证据。

#### Scenario: 匀速刷里程触发 R1
GIVEN 滑动 10 点平均速度 >5.5 m/s 且持续 ≥10 点
WHEN R1 速度规则运行
THEN 命中 R1_SPEED
AND 级别 HARD
AND 证据含窗口均值与起止点序号

#### Scenario: 飞点拼接触发 R2
GIVEN 相邻有效点加速度 Δv/Δt >3 m/s² 出现 ≥3 次
WHEN R2 加速度规则运行
THEN 命中 R2_ACCEL
AND 级别 SOFT

#### Scenario: 原地抖动触发 R3
GIVEN 存在连续 ≥5min 位移 <5m 的段且段占比 >40% 总时长
WHEN R3 停留规则运行
THEN 命中 R3_STAY
AND 级别 HARD

#### Scenario: 折返刷里程触发 R4
GIVEN 累计轨迹距离/起终点直线距离 >3.0
WHEN R4 距离一致性规则运行
THEN 命中 R4_DISTANCE
AND 级别 SOFT

#### Scenario: 无命中
GIVEN 所有规则均未触发
WHEN 规则链运行完毕
THEN 无 rule_hits 命中记录

### Requirement: 判定聚合
WHEN 规则链执行完毕,
系统 SHALL 按「命中 HARD→REJECTED；仅 SOFT→默认 REJECTED(可配)；无命中→PASSED」聚合判定，并 SHALL 以 `score = 50 + 20×HARD数 + 10×SOFT数` 计算分数（0-100）。

#### Scenario: 命中 HARD 拒绝
GIVEN 命中至少一条 HARD 规则
WHEN 聚合判定
THEN verdict=REJECTED
AND score = 50 + 20×HARD数 + 10×SOFT数

#### Scenario: 仅 SOFT 默认拒绝
GIVEN 仅命中 SOFT 规则且未配置宽松策略
WHEN 聚合判定
THEN verdict=REJECTED

#### Scenario: 无命中通过
GIVEN 无任何规则命中
WHEN 聚合判定
THEN verdict=PASSED

#### Scenario: 证据 JSON 完整
GIVEN 判定完成
WHEN 落库 verification_result
THEN 证据 JSON 含 verdict/score/hits[rule+level+detail]/preprocess

### Requirement: 校验状态机
WHEN 校验与申诉流转,
系统 SHALL 遵循 SUBMITTED→VERIFYING→PASSED/REJECTED 与 REJECTED→APPEALING→RE_PASSED/RE_CONFIRMED，且 SHALL 以乐观锁 `UPDATE ... WHERE status AND version` 保证并发安全。

#### Scenario: 校验通过
GIVEN 记录处于 VERIFYING 且判定 verdict=PASSED
WHEN verify 回调
THEN 状态迁至 PASSED
AND 发 VERIFIED 事件

#### Scenario: 校验拒绝
GIVEN 记录处于 VERIFYING 且 verdict=REJECTED
WHEN verify 回调
THEN 状态迁至 REJECTED
AND 发 REJECTED 事件并保存证据

#### Scenario: 申诉提交
GIVEN 记录处于 REJECTED
WHEN 用户提交申诉
THEN 状态迁至 APPEALING
AND 建 appeal 单（record_id 唯一）

#### Scenario: 终判改判
GIVEN 记录处于 APPEALING 且 appeal 为 PENDING
WHEN 管理员复核通过
THEN 状态迁至 RE_PASSED
AND 发 VERIFIED 事件

#### Scenario: 终判维持拒绝
GIVEN 记录处于 APPEALING 且 appeal 为 PENDING
WHEN 管理员复核确认
THEN 状态迁至 RE_CONFIRMED
AND 发 REJECTED 事件

#### Scenario: 并发冲突
GIVEN 两个请求同时迁移同一记录
WHEN 乐观锁 WHERE status AND version 执行
THEN 仅一个影响行数为 1
AND 另一个影响 0 行，报 3003 或重试

### Requirement: 校验事件与幂等
WHEN 校验流程产生状态变化,
系统 SHALL 经 RocketMQ 发布 SUBMITTED/VERIFIED/REJECTED 事件，并 SHALL 以 eventId 去重保证消费幂等。

#### Scenario: 触发校验事件
GIVEN 记录提交进入 VERIFYING
WHEN 状态迁移完成
THEN 发布 SUBMITTED 事件（Tag 区分）
AND verify 消费后拉轨迹执行判定并回调

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

### Requirement: 规则阈值可配置
WHEN 规则链读取阈值,
系统 SHALL 从 Nacos 配置 `verify.rules.*` 获取，且 SHALL 提供与审批版 §5.2 一致的默认值。

#### Scenario: 默认阈值生效
GIVEN Nacos 无覆盖配置
WHEN 规则链初始化
THEN 使用默认值 V_DRIFT=20、R1=5.5、R2=3、R3 段占比 40%、R4=3.0

#### Scenario: 覆盖阈值生效
GIVEN Nacos 配置 verify.rules.r1.speed=6.0
WHEN 规则链读取
THEN R1 阈值采用 6.0
AND 其他阈值采用默认值

---

## 备注

- 本变更实现「校验闭环」主线；榜单贡献快照、排行榜入榜、Nacos 灰度发布属后续独立变更。
- 状态机迁移矩阵与阈值默认值均以审批版 §5.1/§5.2 为唯一依据。
- 取消「仅 SOFT→REJECTED」的宽松开关时机由后续灰度变更决定，本变更只提供可配置项。