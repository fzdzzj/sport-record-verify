# 规范：运动记录真实性校验系统（sport-record-verify）

> 首个能力域规范基线，由变更提案 `spec/changes/add-microservice-skeleton/` 落地生成。
> 校验引擎、好友、点赞、排行榜等业务需求在后续变更中分别以 ADDED 需求补充。

## 本规范已归档以下提案

- add-verify-engine（校验引擎）
- add-friend-module（好友）
- add-like-module（点赞）
- add-leaderboard-module（榜单）
- add-load-test-report（压测）
- add-observability（可观测性）
- add-rule-grayscale（规则灰度）
- add-leaderboard-service（独立榜单服务）
- add-jwt-auth（鉴权）
- add-mapmatch-service（空间匹配）
- add-admin-rbac（治理面鉴权）
- add-sport-type-threshold（阈值分类型）
- add-login-lockout（账号锁定）

各提案的 spec-delta 中 ADDED 需求已全部合并进本规范，MODIFIED 需求按规则处理（见「服务划分」分组与「变更历史」）。

## 工程结构

### Requirement: 多模块工程结构

WHEN 工程被构建,
系统 SHALL 产出父工程与 8 个可编译模块（common、api、gateway-service、user-service、record-service、verify-service、leaderboard-service），并 SHALL 通过 `mvn clean install`。

#### Scenario: 全量构建成功

GIVEN 本地已安装 JDK 21 与 Maven
WHEN 执行 `mvn clean install`
THEN 所有模块编译打包成功
AND 无快照依赖缺失报错

#### Scenario: 版本不匹配被拦截

GIVEN 本地 JDK 低于 21
WHEN 执行构建
THEN 编译失败
AND 错误信息明确提示 JDK 版本要求

### Requirement: 依赖版本集中锁定

WHERE 任一子模块声明依赖,
系统 SHALL 由父工程 dependencyManagement 统一管理版本，子模块 SHALL 不重复声明版本号。

#### Scenario: 子模块引用统一版本

GIVEN 子模块声明 mybatis-plus 依赖且未写版本号
WHEN 构建解析依赖
THEN 版本继承父工程锁定值
AND 与官方兼容矩阵一致

#### Scenario: 越界版本被拒绝

GIVEN 子模块引入与锁定矩阵不兼容的 Boot 3.3
WHEN 构建解析依赖
THEN 依赖树出现与 SCA 2023 分支不兼容冲突
AND 由版本矩阵约束阻止升级

### Requirement: 统一响应与错误码

WHEN 任一服务返回结果,
系统 SHALL 使用统一 `Result<T>` 包装，失败时 SHALL 返回结构化错误码与信息。

#### Scenario: 成功响应包装

GIVEN 服务处理成功
WHEN 返回结果
THEN 响应体为 `{code:0, message:"ok", data:<payload>}`

#### Scenario: 业务异常

GIVEN 服务抛出业务异常
WHEN 全局异常处理器拦截
THEN 返回对应错误码与可读信息
AND 不泄漏堆栈细节

### Requirement: 服务间 Feign 契约

WHEN 服务间发生调用,
系统 SHALL 通过 api 模块定义的 Feign 接口与 DTO 完成，接口定义与实现 SHALL 分离。

#### Scenario: 跨服务探活

GIVEN verify-service 需要调用 record-service 的健康端点
WHEN verify-service 发起 Feign 调用
THEN 请求经 api 模块接口定义路由
AND 返回结果与 DTO 契约一致

#### Scenario: 契约漂移

GIVEN api 模块 DTO 字段与实现不一致
WHEN 编译或运行时反序列化
THEN 编译失败或反序列化报错
AND 提示契约不一致

### Requirement: 服务注册与配置中心

WHEN 任一服务启动,
系统 SHALL 注册到 Nacos 注册中心并从 Nacos 配置中心拉取配置。

#### Scenario: 正常注册

GIVEN Nacos 已启动
WHEN 服务启动完成
THEN Nacos 控制台可见该服务实例
AND 服务成功拉取远程配置

#### Scenario: 注册中心不可用

GIVEN Nacos 未启动
WHEN 服务启动
THEN 启动失败或持续重试注册
AND 日志明确指向 Nacos 连接失败

### Requirement: 中间件一键编排

WHEN 开发者执行 `docker compose up`,
系统 SHALL 一键启动 Nacos、MySQL×3、Redis、RocketMQ，并保证启动依赖顺序与健康就绪。

#### Scenario: 一键起全部中间件

GIVEN 已安装 Docker
WHEN 执行 `docker compose up`
THEN 各中间件按依赖顺序启动
AND 健康检查全部就绪

#### Scenario: 依赖未就绪被等待

GIVEN MySQL 尚未健康
WHEN 依赖 MySQL 的容器启动
THEN 容器等待 MySQL 健康检查通过后再启动

### Requirement: 数据库初始化

WHEN 首次启动各服务,
系统 SHALL 执行审批版 §6 建表脚本，初始化 user_db / record_db / verify_db 中的全部表。

#### Scenario: 首次建表成功

GIVEN 三个库为空
WHEN 执行建表脚本
THEN 全部表创建成功
AND 字段与审批版 §6 定义一致

#### Scenario: 幂等重放

GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

### Requirement: 网关统一入口

WHEN 客户端发起请求,
系统 SHALL 经 gateway-service 路由到目标服务，不直接暴露服务实例地址。

#### Scenario: 路由成功

GIVEN gateway 与目标服务均已启动
WHEN 客户端请求网关路径 `/record/**`
THEN 请求被转发到 record-service
AND 返回目标服务响应

#### Scenario: 目标不可达

GIVEN 目标服务未启动
WHEN 客户端请求对应网关路径
THEN 网关返回 502/503
AND 错误不泄漏内部拓扑

## 校验引擎

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

## 好友

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

## 点赞

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

## 榜单

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

## 榜单服务

### Requirement: 独立榜单服务

WHEN 系统初始化服务,
系统 SHALL 提供独立的 `leaderboard-service` 承载榜单读热与事件沉淀，与 record-service 的职责 SHALL 分离（record=记录读写，leaderboard=榜单）。

#### Scenario: 服务注册

GIVEN leaderboard-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 leaderboard-service 独立实例
AND 独立端口（默认 8084）

#### Scenario: 路由可达

GIVEN 网关已配置 /leaderboard/** 路由
WHEN 客户端请求 /leaderboard/api/leaderboard?type=overall
THEN 返回总榜
AND 请求转发至 leaderboard-service

### Requirement: 榜单事件订阅独立

WHEN 校验产生 VERIFIED/REJECTED 事件,
系统 SHALL 由 leaderboard-service 以独立消费组订阅并沉淀榜单，record-service 的榜单消费者 SHALL 下线，避免双写。

#### Scenario: 独立消费组

GIVEN 记录通过校验并发 VERIFIED 事件
WHEN 事件被消费
THEN 仅 leaderboard-service 入榜
AND record-service 不再写入榜单

#### Scenario: 回滚由榜单服务处理

GIVEN 记录改判发 REJECTED 事件
WHEN leaderboard-service 消费
THEN 回滚榜单贡献
AND 结果与迁移前 T8 验收一致

### Requirement: 榜单数据依赖

WHEN leaderboard-service 沉淀榜单,
系统 SHALL 读取 `leaderboard_contribution` 表；该表默认复用 record_db，物理隔离到独立 leaderboard_db SHALL 列为可选。

#### Scenario: 复用 record_db

GIVEN 默认配置
WHEN leaderboard-service 读写贡献表
THEN 使用 record_db（与 record-service 共享该库中贡献表）
AND 榜单查询功能正常

#### Scenario: 独立 leaderboard_db（可选）

GIVEN 选择物理隔离
WHEN leaderboard-service 启动
THEN 连接独立 leaderboard_db
AND 贡献表迁入该库

## 服务划分

### Requirement: 服务划分

系统 SHALL 由 6 个服务构成：gateway-service、user-service、record-service、verify-service、leaderboard-service、mapmatch-service；WHEN 系统部署, 榜单职责 SHALL 由 leaderboard-service 独立承载，道路拓扑匹配 SHALL 由 mapmatch-service 独立承载，离路判定 R5 SHALL 内聚于 verify-service 并远程调用匹配服务。

> 变更说明：本需求由提案 add-leaderboard-service 与 add-mapmatch-service 两次 MODIFIED「服务划分」演进而来。系统从 4 个服务（gateway-service、user-service、record-service、verify-service，榜单内聚于 record-service）演进到 5 个服务（追加 leaderboard-service，榜单职责独立承载），再到 6 个服务（追加 mapmatch-service，道路拓扑匹配职责独立承载），服务数 4→5→6。

#### Scenario: 服务数

GIVEN 系统完整部署
WHEN 查看服务实例
THEN 可见 6 个服务各自注册
AND 空间匹配职责在 mapmatch-service 不在 verify-service

## 压测

### Requirement: 并发压测方法

WHEN 系统进行性能验证,
系统 SHALL 对提交记录接口发起 100/500/1000 三档并发，并 SHALL 记录 P95、P99、QPS、错误率。

#### Scenario: 三档并发执行

GIVEN 压测脚本就绪，环境快照固定
WHEN 依次以 100、500、1000 并发压测提交接口
THEN 每档记录 P95/P99/QPS/错误率
AND 原始数据留存供优化前后对比

#### Scenario: 环境可复现

GIVEN 压测结论被引用
WHEN 复现压测
THEN 脚本与文档可重复执行
AND 记录环境快照（JDK/内存/中间件版本）保证可比

### Requirement: 量化达标

WHEN 系统以测试集（≥200 条，正负各半）验收,
系统 SHALL 达到拦截率 ≥90%、真实通过率 ≥95%、校验 P95 <200ms。

#### Scenario: 拦截率达标

GIVEN 伪造样本集
WHEN 运行校验引擎
THEN 拦截率 ≥90%

#### Scenario: 通过率达标

GIVEN 真实样本集
WHEN 运行校验引擎
THEN 通过率 ≥95%

#### Scenario: 延迟达标

GIVEN 校验链路运行
WHEN 统计响应时间
THEN P95 <200ms

#### Scenario: 未达标如实记录

GIVEN 任一指标未达标
WHEN 验收
THEN 如实记录实测值
AND 定位瓶颈并记录优化过程（不夸大）

### Requirement: 瓶颈优化实录

WHEN 压测暴露性能瓶颈,
系统 SHALL 定位并优化，且 SHALL 产出至少 1 个 Explain 慢查询案例与 1 个 GC/连接池调优案例，并 SHALL 记录优化前后对比。

#### Scenario: 慢查询案例

GIVEN 压测发现慢 SQL
WHEN 用 Explain 分析
THEN 加索引或改写 SQL
AND 记录优化前后 Explain 与耗时对比

#### Scenario: GC/连接池案例

GIVEN 压测发现 GC 停顿或连接池瓶颈
WHEN 调优
THEN 记录优化前后 GC 停顿或吞吐对比
AND 形成因果可解释的调优案例

### Requirement: 限流与熔断验证

WHEN 系统面对高并发或依赖故障,
系统 SHALL 由 Sentinel 在网关限流并对齐 5k QPS 目标，且 verify 不可用时 SHALL 熔断降级为「转人工」，主链路 SHALL 不挂。

#### Scenario: 限流拦截

GIVEN 请求超过限流阈值
WHEN 网关处理
THEN 超限请求被限流拦截
AND 返回限流提示

#### Scenario: 熔断降级转人工

GIVEN verify-service 不可用
WHEN record 侧调用校验
THEN 熔断降级为「转人工」状态
AND 提交主链路不挂（不因校验故障整体失败）

### Requirement: 压测沉淀

WHEN 压测与优化完成,
系统 SHALL 产出压测报告与 ADR，将方案、数据、优化因果 SHALL 写入 README/ADR 可复现文档。

#### Scenario: 报告产出

GIVEN 压测与优化已执行
WHEN 沉淀阶段
THEN 产出 docs/perf/压测报告.md（方案/环境/数据/图表/对比/结论）
AND 产出 ADR 记录优化因果
AND README 补摘要与复现命令

## 可观测性

### Requirement: 指标暴露

WHEN 任一服务运行,
系统 SHALL 经 `/actuator/prometheus` 暴露 Micrometer 指标，涵盖 JVM（堆/GC/线程）、HTTP（QPS/P95/错误率）、数据源连接池与业务判定指标。

#### Scenario: 端点可访问

GIVEN 服务已启动且依赖 micrometer-registry-prometheus 就绪
WHEN 请求 `/actuator/prometheus`
THEN 返回 Prometheus 文本格式指标
AND 包含 `jvm_` 与 `http_server_requests_` 前缀指标

#### Scenario: 端点未开启即不可达

GIVEN 服务未在 management 中暴露 prometheus 端点
WHEN 请求 `/actuator/prometheus`
THEN 返回 404 或隐藏
AND 不泄漏额外指标

### Requirement: 指标采集

WHEN Prometheus 运行,
系统 SHALL 按 prometheus.yml 静态配置抓取全部 5 个服务（gateway/user/record/verify + 未来 leaderboard）的指标端点。

#### Scenario: 抓取成功

GIVEN Prometheus 与服务均运行
WHEN 查看 Prometheus targets
THEN 各服务 target 状态为 UP
AND 指标带 instance 标签区分

#### Scenario: 实例下线可见

GIVEN 某服务停止
WHEN Prometheus 下一抓取周期
THEN 该 target 标记 DOWN
AND 触发对应告警

### Requirement: 可视化面板

WHEN 运维查看监控,
系统 SHALL 提供一个 Grafana Dashboard 展示核心指标：服务可用性、HTTP P95/错误率、JVM 堆/GC、连接池。

#### Scenario: 面板展示

GIVEN Grafana 已配置 Prometheus datasource
WHEN 打开预置 dashboard
THEN 展示服务可用性、延迟、错误率、JVM 面板
AND 数据来自 Prometheus

### Requirement: 告警规则

WHEN 指标越过阈值,
系统 SHALL 触发告警，至少覆盖：实例下线、HTTP 错误率超阈值、校验 P95 >200ms（对齐审批版 §8.2）、JVM 堆使用率 >80%。

#### Scenario: 延迟告警

GIVEN 校验接口 P95 超过 200ms 持续一段时间
WHEN Prometheus 评估告警规则
THEN 触发 P95 告警（firing 状态）

#### Scenario: 实例下线告警

GIVEN 某服务实例停止
WHEN Prometheus 检测 target DOWN
THEN 触发实例下线告警

## 规则灰度

### Requirement: 规则版本化

WHEN 管理员创建规则版本,
系统 SHALL 将当前规则与阈值序列化为 `rules_json` 快照存入 rule_version，并 SHALL 维护版本状态（GRAY/ACTIVE/RETIRED）与 `gray_ratio`。

#### Scenario: 创建灰度版本

GIVEN 当前基线规则阈值
WHEN 管理员创建新版本并设 gray_ratio=10
THEN 快照 rules_json 落库
AND 版本状态 GRAY
AND 灰度比例 10

#### Scenario: 版本状态约束

GIVEN 已存在一个 ACTIVE 基线版本
WHEN 新版本被标记为 ACTIVE
THEN 旧版本置 RETIRED
AND 同一时刻至多一个 ACTIVE 版本

### Requirement: 灰度采样路由

WHEN 校验引擎执行,
系统 SHALL 按 `userId % 100 < gray_ratio` 决定使用灰度规则快照或基线规则，且 SHALL 保证同一用户始终同一分支。

#### Scenario: 命中灰度

GIVEN gray_ratio=10
AND 用户 userId%100=5（<10）
WHEN 该用户提交记录触发校验
THEN 使用灰度规则快照执行

#### Scenario: 未命中灰度

GIVEN gray_ratio=10
AND 用户 userId%100=50（≥10）
WHEN 该用户提交记录触发校验
THEN 使用基线规则执行

#### Scenario: 采样稳定性

GIVEN 同一用户重复提交
WHEN 多次触发校验
THEN 每次均命中同一分支（灰/基线）
AND 不因请求时序抖动切换分支

### Requirement: 规则快照隔离

WHEN 灰度观察期执行规则,
系统 SHALL 使用库内 `rules_json` 快照而非 Nacos 实时配置，避免灰度期间配置变更导致规则漂移。

#### Scenario: 灰度用快照

GIVEN 灰度版本观察中
AND Nacos 实时阈值此时被修改
WHEN 校验引擎取规则
THEN 灰度分支仍用版本快照执行
AND 不受 Nacos 瞬时变更影响

### Requirement: 秒级回滚

WHEN 灰度版本判定异常,
系统 SHALL 支持将 `gray_ratio` 置 0 秒级回滚，新版本 SHALL 立即不再被采样，基线 SHALL 不受影响。

#### Scenario: 回滚生效

GIVEN 灰度版本 gray_ratio=10 且出现异常
WHEN 管理员将 gray_ratio 置 0
THEN 短 TTL 缓存失效后（≤60s）新版本不再采样
AND 全部请求回归基线规则

#### Scenario: 基线性不受影响

GIVEN 灰度版本运行中
WHEN 回滚 gray_ratio=0
THEN 基线规则持续正常运行
AND 无请求中断

### Requirement: 全量发布

WHEN 灰度版本稳定,
系统 SHALL 支持将 gray_ratio 置 100 全量发布，并 SHALL 将旧版本置 RETIRED。

#### Scenario: 全量生效

GIVEN 灰度版本稳定运行满观察期
WHEN 管理员执行全量发布
THEN gray_ratio=100
AND 全部用户使用新版本规则

#### Scenario: 旧版本退役

GIVEN 新版本已全量
WHEN 发布完成
THEN 旧 ACTIVE 版本置 RETIRED
AND 不再被采样

### Requirement: 版本管理接口

WHEN 管理员管理规则,
系统 SHALL 提供版本管理端点：创建版本、调整灰度比例、全量发布。

#### Scenario: 创建与调灰度

GIVEN 管理员调用管理端点
WHEN 创建版本并调整 gray_ratio
THEN 返回版本信息与最新灰度比例

#### Scenario: 全量发布成功

GIVEN 存在 GRAY 版本
WHEN 调用全量发布端点
THEN 版本置 ACTIVE
AND 旧版本 RETIRED

## 鉴权

### Requirement: 用户注册

WHEN 用户提交注册,
系统 SHALL 校验手机号唯一并 SHALL 使用 BCrypt 哈希存储密码，重复手机号 SHALL 返回 2001。

#### Scenario: 注册成功

GIVEN 用户提交新手机号与密码
WHEN 注册接口处理
THEN 创建用户（密码 BCrypt 哈希）
AND 返回注册成功

#### Scenario: 手机号重复

GIVEN 手机号已存在注册用户
WHEN 再次注册同一手机号
THEN 返回 2001（手机号已注册）
AND 不创建新用户

### Requirement: 用户登录

WHEN 用户提交凭据,
系统 SHALL 校验密码，成功 SHALL 签发 access token 与 refresh token，失败 SHALL 返回 401 并计失败次数。

#### Scenario: 登录成功

GIVEN 手机号与密码正确
WHEN 登录接口处理
THEN 返回 access token 与 refresh token
AND access 短时效、refresh 长时效

#### Scenario: 密码错误

GIVEN 密码错误
WHEN 登录接口处理
THEN 返回 401
AND 计失败次数（超过阈值锁定的设计口径）

### Requirement: Token 刷新与轮换

WHEN access token 过期,
系统 SHALL 凭 refresh token 刷新，且 SHALL 轮换（旧 refresh 作废，新 refresh 下发）。

#### Scenario: 刷新成功

GIVEN refresh token 有效且未作废
WHEN 调用刷新接口
THEN 返回新 access 与新 refresh
AND 旧 refresh 作废

#### Scenario: refresh 已作废

GIVEN refresh token 已轮换过或过期
WHEN 调用刷新接口
THEN 返回 401（1001）

### Requirement: 网关统一鉴权

WHEN 请求进入网关,
系统 SHALL 校验 Authorization 头中的 token，失败 SHALL 返回 401（1001），成功 SHALL 解析 userId 并注入请求头透传下游。

#### Scenario: 有效 token 放行

GIVEN 请求携带有效 Bearer token
WHEN 网关过滤器处理
THEN 解析出 userId
AND 注入 X-User-Id 头透传下游

#### Scenario: 无效 token 拒绝

GIVEN 请求无 token 或 token 无效/过期
WHEN 网关过滤器处理
THEN 返回 401（1001）
AND 不放行至下游

#### Scenario: 白名单放行

GIVEN 请求路径属白名单（/api/auth、/internal、/actuator）
WHEN 网关过滤器处理
THEN 跳过鉴权直接放行

### Requirement: 用户数据隔离

WHEN 业务接口处理用户请求,
系统 SHALL 从网关注入的 userId 认定身份，而非信任调用方传入的 userId，越权访问他人资源 SHALL 返回 403（1002）。

#### Scenario: 正常访问本人数据

GIVEN 用户 A 携带自己的 token
WHEN 访问本人记录/点赞/好友
THEN 以 token 中 userId 为准处理
AND 返回正常结果

#### Scenario: 越权访问他人被拒

GIVEN 用户 A 尝试访问用户 B 的资源
WHEN 业务判定 userId 不一致
THEN 返回 403（1002）
AND 不泄露 B 的数据

### Requirement: 鉴权降级开关

WHEN 本地调试或压测需要,
系统 SHALL 提供 auth.enabled 开关，关闭时 SHALL 降级为显式携带 userId 的旧行为，默认关闭以降低迁移成本。

#### Scenario: 开关默认关闭

GIVEN auth.enabled=false（默认）
WHEN 请求受保护接口
THEN 沿用显式携带 userId 的旧行为
AND 不启用网关鉴权

#### Scenario: 开关启用

GIVEN auth.enabled=true
WHEN 请求受保护接口
THEN 强制走网关鉴权与数据隔离

### Requirement: 用户角色模型

WHEN 用户注册,
系统 SHALL 默认赋予 USER 角色，且 SHALL 提供内部接口授予 ADMIN 角色（最小权限，默认非管理员）。

#### Scenario: 注册默认 USER

GIVEN 新用户注册成功
WHEN 查询其角色
THEN 角色为 USER

#### Scenario: 内部授予 ADMIN

GIVEN 内部管理流程
WHEN 调用授予接口
THEN 指定用户角色变更为 ADMIN

### Requirement: 令牌携带角色

WHEN 登录签发令牌,
系统 SHALL 在 token 中写入 role claim，且 role SHALL 由签发端决定，SHALL 不被下游信任外部传入。

#### Scenario: token 含角色

GIVEN 用户登录成功
WHEN 解析 access token
THEN 可读取出 role claim 与 userId

### Requirement: 治理面鉴权

WHEN 请求访问管理端接口（/admin/** 或规则版本接口）,
系统 SHALL 要求 role=ADMIN，普通用户 SHALL 返回 403（1002），未登录 SHALL 返回 401（1001）。

#### Scenario: 管理员访问放行

GIVEN 请求携带 ADMIN 角色的有效 token
WHEN 访问管理端接口
THEN 放行至下游

#### Scenario: 普通用户被拒

GIVEN 请求携带 USER 角色的有效 token
WHEN 访问管理端接口
THEN 返回 403（1002）

#### Scenario: 未登录被拒

GIVEN 请求无有效 token
WHEN 访问管理端接口
THEN 返回 401（1001）

### Requirement: 白名单收紧

WHEN 网关过滤请求,
系统 SHALL 不为管理端接口提供匿名放行，且 SHALL 保持内部接口（/internal/**）网内信任边界。

#### Scenario: 管理端不匿名放行

GIVEN 请求路径为 /admin/**
WHEN 网关过滤
THEN 进入鉴权校验（不跳过）
AND 依角色判定放行或拒绝

#### Scenario: 内部接口维持网内

GIVEN 请求路径为 /internal/**
WHEN 网关过滤
THEN 维持网内信任（不对公网暴露）

## 空间匹配

### Requirement: 独立路网匹配服务

WHEN 校验需要空间真实性判定,
系统 SHALL 提供独立的 `mapmatch-service` 承载路网数据与匹配算法，暴露匹配接口，与 verify-service 的规则链 SHALL 解耦。

#### Scenario: 服务注册

GIVEN mapmatch-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 mapmatch-service 独立实例
AND 独立端口（默认 8085）

#### Scenario: 匹配接口返回

GIVEN 路网数据已加载
WHEN 调用 POST /mapmatch/match 提交轨迹点
THEN 返回匹配结果（matchedRatio / avgOffRoadDistance / offRoadRatio 等）

### Requirement: 真实路网数据

WHEN 路网服务初始化,
系统 SHALL 加载真实 OSM 路网数据（而非手工假数据），并提供幂等可重跑的导入流程。

#### Scenario: 路网导入成功

GIVEN 已下载指定城市 OSM 路网
WHEN 执行导入脚本
THEN 路网表写入空间库
AND 导入脚本幂等可重跑

#### Scenario: 数据可查询

GIVEN 路网已导入
WHEN 匹配算法查询最近道路
THEN 基于空间索引返回候选道路
AND 查询命中实际路网

### Requirement: R5 离路规则

WHEN 校验引擎执行规则链,
系统 SHALL 新增 R5 离路规则，通过路网匹配计算轨迹偏离真实道路的比例，偏离超阈值 SHALL 产生 SOFT/HARD 证据。

#### Scenario: 悬浮轨迹命中 R5

GIVEN 轨迹整体不在任何真实道路上（如海面/楼顶）
WHEN R5 执行路网匹配
THEN offRoadRatio 超过阈值
AND 命中 R5_OFFROAD 证据

#### Scenario: 真实轨迹不命中

GIVEN 真实骑行/跑步轨迹沿道路
WHEN R5 执行路网匹配
THEN offRoadRatio 低于阈值
AND 不产生 R5 命中

### Requirement: 判定聚合兼容

WHEN R5 产生证据,
系统 SHALL 将 R5 命中并入既有 hits 列表，走现有「HARD 即拒 / SOFT 计分」聚合，SHALL 不改变 R1-R4 既有行为。

#### Scenario: R5 软证据参与评分

GIVEN R5 命中 SOFT
WHEN 判定聚合
THEN score 计入 10×SOFT 项
AND 仅 SOFT 时遵循 soft-only-reject 策略

#### Scenario: R5 硬证据即拒

GIVEN R5 命中 HARD（极端偏离）
WHEN 判定聚合
THEN verdict=REJECTED

### Requirement: 匹配降级

WHEN mapmatch-service 不可用,
系统 SHALL 使 R5 降级为「不命中」，不阻断校验主链路，并 SHALL 记录告警。

#### Scenario: 服务不可用降级

GIVEN mapmatch-service 停机
WHEN R5 调用匹配接口失败
THEN 熔断降级为不命中
AND 校验主链路正常完成
AND 记录 warn 日志

## 阈值分类型

### Requirement: 记录携带运动类型

WHEN 用户提交运动记录,
系统 SHALL 接收 sportType 字段，缺省 SHALL 回退为 RUNNING（向后兼容），未知类型 SHALL 拒绝。

#### Scenario: 提交带类型

GIVEN 用户提交记录并指定 sportType=CYCLING
WHEN 记录入库
THEN sportType 字段正确保存

#### Scenario: 缺省回退

GIVEN 提交记录未指定 sportType
WHEN 记录入库
THEN sportType 回退为 RUNNING
AND 校验行为与现有 RUNNING 一致

#### Scenario: 未知类型拒绝

GIVEN 提交记录 sportType 为枚举外取值
WHEN 记录提交接口处理
THEN 返回非法参数错误

### Requirement: 阈值按类型分维度

WHEN 系统配置规则阈值,
系统 SHALL 按运动类型分维度（每类型一套 R1-R4 阈值），而非全类型共用一套。

#### Scenario: 类型独立阈值

GIVEN 规则快照含 RUNNING 与 CYCLING 两套阈值
WHEN 读取阈值
THEN RUNNING 与 CYCLING 各自独立
AND 互不影响

### Requirement: 按类型判定

WHEN 校验引擎判定记录,
系统 SHALL 依据记录的 sportType 取对应阈值集执行 R1-R4，未知类型 SHALL 保守处理（回退默认或保守拒绝，可配）。

#### Scenario: 骑行不误杀

GIVEN 一条真实骑行轨迹 sportType=CYCLING
WHEN 引擎判定
THEN 取 CYCLING 阈值（速度上限高于跑步）
AND 不触发 R1 误判

#### Scenario: 跑步沿用原阈值

GIVEN 一条跑步轨迹 sportType=RUNNING
WHEN 引擎判定
THEN 取 RUNNING 阈值（沿用现有 5.5）
AND 行为与历史一致

### Requirement: 灰度与类型维度正交

WHEN 灰度路由与类型阈值叠加,
系统 SHALL 保持两维度正交：userId%100 决定使用哪个规则版本，sportType 决定版本内用哪套阈值，SHALL 不改动现有灰度路由逻辑。

#### Scenario: 正交叠加

GIVEN 某版本快照含多类型阈值
AND 用户命中灰度
WHEN 判定
THEN 先按灰度取版本，再按 sportType 取阈值
AND 灰度路由逻辑不变

## 账号锁定

### Requirement: 登录失败锁定

WHEN 同一手机号在窗口期内连续登录失败达阈值,
系统 SHALL 锁定该账号，锁定期间 SHALL 拒绝登录且不校验密码，锁定期满 SHALL 自动解锁。

#### Scenario: 达阈值触发锁定

GIVEN 手机号在 15 分钟窗口内已失败 5 次
WHEN 再次登录（第 6 次）
THEN 系统拒绝登录
AND 不校验密码（直接返回锁定）
AND 锁定开始计时

#### Scenario: 锁定期间拒绝

GIVEN 账号处于锁定状态
WHEN 用户提交任意密码登录
THEN 返回「账号已临时锁定」
AND 不消耗 BCrypt 校验
AND 不更新失败计数

#### Scenario: 锁定期满自动解锁

GIVEN 账号锁定已达到锁定时长
WHEN 用户登录
THEN 锁定键过期（TTL 到期）
AND 恢复校验密码

### Requirement: 登录成功清零

WHEN 用户登录成功,
系统 SHALL 清除该手机号的失败计数与锁定状态，防止「试错后纠正」导致的计数残留。

#### Scenario: 成功清零

GIVEN 手机号有若干失败计数（未达阈值）
WHEN 该用户登录成功
THEN 失败计数清零
AND 若存在锁定键则一并清除

### Requirement: 防爆破与降级

WHEN 系统实施锁定,
系统 SHALL 不区分「用户不存在」与「密码错误」（统一返回，防撞库探测），且 Redis 不可用时 SHALL 降级为「继续计数告警但不阻断登录」。

#### Scenario: 防撞库探测

GIVEN 登录失败
WHEN 返回错误
THEN 用户不存在与密码错误返回同一语义
AND 不泄露账号是否存在

#### Scenario: Redis 不可用降级

GIVEN Redis 不可用
WHEN 执行登录失败计数与锁定
THEN 降级为仅告警不阻断
AND 登录流程不因 Redis 故障失败

## 变更历史

各提案 spec-delta 备注中有价值的上下文说明，融合记录如下：

- **add-verify-engine**：实现「校验闭环」主线；榜单贡献快照、排行榜入榜、Nacos 灰度发布属后续独立变更（已分别落地）。状态机迁移矩阵与阈值默认值以审批版 §5.1/§5.2 为唯一依据。取消「仅 SOFT→REJECTED」的宽松开关时机由灰度变更决定（见「规则灰度」分组）。
- **add-friend-module**：关系一旦 ACCEPTED 双方互见，存储层以 `(user_low,user_high)` 单向归一化承载双向关系。好友榜（经 UserApi 过滤）由「榜单」分组承接。状态机字段值与错误码（5001/5002/2002）以审批版 §4.1/§6.1/§4.8 为唯一依据。
- **add-like-module**：点赞只对 PASSED 记录开放，与校验引擎状态机强耦合（落地顺序在其后）。计数权威源为 record_like 行，Redis 为读热写冷的加速层（Redis 原子计数 + 最终一致 + 异步批量）。错误码 6001 与 record_like 表结构以审批版 §4.6/§4.8/§6.2 为唯一依据。
- **add-leaderboard-module**：榜单是校验闭环的收口，依赖校验引擎（事件）与好友模块（好友列表 Feign）。ZSet `leaderboard:overall`（member=userId，score=累计 pass 里程）为热读层；leaderboard_contribution 行为权威源与回滚锚点（Redis ZSet + 事件驱动最终一致 + 定时防重）。事件 Tag 与表结构以审批版 §4.5/§6.2/§7.1/§7.5 为唯一依据。
- **add-leaderboard-service**：架构重构，不新增业务功能，把已实现的榜单从 record-service 平移至独立服务，服务数 4→5。「为什么 5 个服务」「榜单为什么独立」成为架构决策 ADR（数据热点隔离、读多写少独立扩缩容、独立降级面）。贡献表归属默认复用 record_db（最小改动），独立 leaderboard_db 为可选项。路由前缀 /record/api/leaderboard → /leaderboard/** 为破坏性变更，需兼容期过渡。
- **add-load-test-report**：不新增业务功能，聚焦「真实数据 + 优化因果」沉淀。指标阈值（90%/95%/200ms）与压测并发档位（100/500/1000）以审批版 §8.2/§9 T12/§12.3 第 9 项与 A1 项为唯一依据。已确认不购云服务器：压测在本地 Docker Compose 环境执行，结论按本地单机能力如实标注（诚实口径）。
- **add-observability**：运维增强，不改变业务功能；指标口径对齐压测报告，形成「即时观测 + 历史实录」双层证据。/actuator/prometheus 本地演示直连；生产安全（网关不转发 actuator、内网抓取、最小权限）属「讲设计」范畴。监控栈选型（Prometheus+Grafana）理由随 ADR 记录。
- **add-rule-grayscale**：把「阈值可配」升级为「版本化灰度发布」，落地审批版 §7.4 全部流程。rule_version 表已建（sql/03-verify-db.sql），本变更不迁移表结构。采样键 userId%100 与审批版 §7.4 一致；灰度观察期用库内快照避免与 Nacos 动态刷新竞态。
- **add-jwt-auth**：收口「骨架无认证」技术债，建立鉴权能力域。双 token + refresh rotation（Redis `GETDEL` 原子消费防重放，access 15min / refresh 7d）；网关统一鉴权（身份由网关以 X-User-Id 唯一认定，下游不信任调用方自报）；用户数据隔离（越权访问他人资源 → 403/1002）；auth.enabled 降级开关（默认关闭降级为显式携带 userId 的旧行为，迁移成本可控）。引用 docs/adr/0007。
- **add-mapmatch-service**：新增独立 mapmatch-service（第 6 服务），把道路拓扑匹配从「讲设计」升级为实锤（执行计划 P2 天花板项），服务数 4→5→6。R5 是首个依赖外部服务的规则（verify 侧 `R5OffRoadRule` 实现 Rule 接口 + Feign 调 mapmatch，Rule 接口可扩展性首获远程规则红利，聚合框架零改动）；真实 OSM 单城市切片 + PostGIS（GIST+R 树）承载路网；R5 默认 SOFT、极端偏离升级 HARD，mapmatch 不可用时熔断降级为「不命中」不阻断校验主链路。引用 docs/adr/0006。
- **add-admin-rbac**：治理面鉴权，与 add-jwt-auth 分工：jwt 管业务面「认身份」，本变更管治理面「授权」（能改规则、能翻案）。USER/ADMIN 最小角色模型（注册默认 USER，ADMIN 仅内部接口显式授予）；access token 携带 role claim（由签发端背书，不信任外部传入）；`/admin/**` 与规则版本接口（RuleVersionController）仅 ADMIN 可达（普通用户 403/1002、未登录 401/1001）；白名单从「裸放行」改为「进链校验角色」；app.auth.admin.enabled 默认启用。引用 docs/adr/0007。
- **add-sport-type-threshold**：引擎从单一运动类型走向多运动类型阈值（消除 GenSamples 已知局限）。语义要点：未知/缺失类型保守回退 RUNNING，与历史行为一致；阈值分类型与灰度路由正交（灰度按 userId%100 路由版本，版本快照内部再按类型分维度，不改灰度逻辑）；rules_json 嵌套升级向后兼容（旧快照缺类型维度时回退单套阈值，仍可解析）。「规则链判定（R1-R4）」本身未改动，仅为其叠加类型维度。
- **add-login-lockout**：账号锁定能力域。口径更正说明：本需求此前长期处于「讲设计」状态（审批版列为能力项、实现只到计数+告警），本次由 add-login-lockout 在代码与规范两侧同时收口，这正是勘误 3 要解决的口径矛盾。实现事实：同一手机号在窗口期（默认 15min）内连续失败达阈值（默认 5 次）→ 写 `auth:lock:{phone}`（Redis + TTL 锁定时长 15min）；达阈值后拒绝登录，不校验密码、不消耗 BCrypt、不更新失败计数；锁定期满 TTL 到期自动解锁；登录成功清除失败计数与锁定键；锁定时「用户不存在」与「密码错误」统一返回（防撞库探测）。