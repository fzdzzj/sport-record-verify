# 提案：写路径事务边界审计（ADR-0009）

## Why

缺少「哪些写必须原子、哪些必须保持最终一致」的书面边界。没有这篇边界，下一步优化很容易走成两极：要么注解铺满，要么该回滚的路径没有测试。

**背景**：
- 已核对：全项目只有 3 处 `@Transactional`（submit / accept / activate）。
- 点赞与榜单是 Redis + DB 的最终一致设计，不是漏加事务。
- 交接里「注册插入 + 角色初始化必须原子」与代码不符：`role` 在 `user` 同一行，注册一次 insert。

全项目只有 3 处 `@Transactional`（已核对代码，不是估计）：

- `SportRecordService.submit`：主记录 + 轨迹点 + 状态迁移，MQ 在 afterCommit
- `FriendService.accept`：申请状态 + 好友行
- `RuleVersionService.activate`：retire + promote，且 `SELECT ... FOR UPDATE` 必须在事务内

交接里「AuthService 4 写 / 0 事务，注册插入+角色初始化必须原子」**与代码不符**：`role` 是 `user` 表字段，注册一次 `insert`，默认 USER。为注册加事务是形式主义。

真正的风险是两类相反的错：

1. **该原子的没测到回滚**（提交轨迹失败是否留下孤儿主记录，单测没覆盖）。
2. **不该进本地事务的被顺手包进去**（点赞 Redis+DB、榜单 ZSet+贡献表、校验 Feign/MQ）。本项目是最终一致 + 幂等；Spring 事务管不了 Redis/Feign。更糟的是：若把 Redis 队列裁剪和 DB 写放进同一个带事务的方法，裁剪发生在 commit 前、随后回滚，会丢掉 pending。

**当前状态**：3 处本地事务已经打在该打的多行 DB 路径上；点赞/榜单刻意最终一致；缺少一篇「为什么这些有/那些没有」的 ADR，也缺少回滚向测试。

**期望状态**：ADR-0009 按路径分类（必须本地原子 / 刻意最终一致 / 单行不必事务 / 跨服务禁止本地长事务）；只补一处真正的脏窗口（点赞 flush 的 INSERT+DELETE）；禁止给 Auth/榜单热路径加事务。

## What Changes

- 新增 `docs/adr/0009-事务边界.md`，用本提案下面的分类表为底稿，执行时对照代码复核，有反例就改表，不准为凑数加注解。
- **允许的唯一生产代码改动**：`RecordLikeService.flushPendingLikes` 把同一批 `batchInsertIgnore` + `batchDelete` 放进本地事务（推荐 `TransactionTemplate`），**Redis 队列裁剪必须在事务成功返回之后**。禁止把 `@Transactional` 直接打在含队列裁剪的方法上。
- 补回滚向单测（Mockito 即可，不引入 Testcontainers）：
  - 提交：轨迹 insert 抛错 → 不注册 afterCommit / 不发 MQ（现有 `inTx` 辅助可复用）
  - 好友 accept：第二写抛错时锁定两写同在带事务的方法内
  - flush：`batchDelete` 抛错 → 不裁剪队列
- 在禁止加事务的方法上补中文注释（like/unlike、applyVerified/rollbackOnRejected、verify、reviewAppeal、register）。
- 规范差异见 spec-delta；速览手册补一句事务边界口径。

**明确不做**：

- 禁止批量给 Service 加 `@Transactional`。
- 禁止给 `AuthService.register/login/grantAdmin` 加事务。
- 禁止用本地事务包裹 Redis ZSet 加减分 / 点赞计数 / Feign / MQ。
- 不上 Seata / 不用本地事务假装跨服务原子。`reviewAppeal`（申诉行已改 + Feign 回调失败）只写入 ADR 已知残余，本变更不修分布式。
- 不改 FriendService 锁与事务的嵌套顺序（已知权衡，不借机重构）。
- 不引入 Testcontainers，不改 CI，不开新业务模块，不动 S1 迁移文件。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED 写路径事务边界、跨服务写不纳入本地事务；MODIFIED 异步批量落库（flush 两写同一本地事务，队列裁剪在成功之后）。

### 受影响的代码
- 新增 ADR-0009
- `record-service`：`RecordLikeService` flush；对应测试
- `record-service` / `user-service`：提交与 accept 的回滚向测试与注释
- `docs/项目速览手册.md` 一句
- 注释：Auth / Leaderboard / Verify 热路径

### 用户影响
- 无 API 变更。点赞 flush 失败窗口缩短（同一批 like+unlike 要么都落要么都回滚）。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（ADR-0009 / 速览 / 规范）

## 时间线评估

小到中：约 0.5-1 天（ADR 是主交付，代码只动 flush + 测试）。

## 风险

- **flush 方法整体加事务导致 Redis 队列先于 DB commit 被裁剪** → 缓解：事务只包 DB 两写，裁剪在模板成功之后；提案写死。
- **把「4 写 0 事务」当成必须补齐** → 缓解：分类表已用代码否定注册多表假设。
- **单测没有 Spring 代理，测不到真库回滚** → 缓解：flush 测「delete 抛错则不裁剪」；提交测「点插入失败则不发 MQ」。真 DB 回滚不做。
- **reviewAppeal 跨服务缺口被当成本变更必须修** → 缓解：ADR 记残余，明确不做 Seata。

## 备注

### 分类表（ADR 底稿，执行时按代码复核）

| 路径 | 写 | 现状 | 结论 |
| --- | --- | --- | --- |
| AuthService.register | user 一行 insert（role 同表默认 USER） | 无事务 | 保持。单行 + 手机号唯一键 |
| AuthService.grantAdmin | 一行 update | 无事务 | 保持 |
| AuthService.login/refresh/锁定 | Redis | 无事务 | 保持。禁止为 Redis 套 Spring 事务 |
| FriendService.createRequest/reject | 单写 + Redisson 锁 | 无事务 | 保持 |
| FriendService.accept | 申请状态 + 好友行 | 已有事务 | 保持。补回滚向测试/注释 |
| SportRecordService.submit | 主记录 + 轨迹 + 状态；MQ afterCommit | 已有事务 | 保持。补点插入失败不发 MQ 测试 |
| SportRecordService.statusCallback | 一行乐观锁 | 无事务 | 保持 |
| RecordLikeService.like/unlike | Redis 集合/计数 + pending 队列 | 无事务 | 禁止加。刻意最终一致 |
| RecordLikeService.flushPendingLikes | 批量插入 + 批量删除，成功后裁剪队列 | 无事务 | 唯一补点：两 DB 写同一本地事务；裁剪在事务成功之后 |
| LeaderboardService.applyVerified/rollbackOnRejected | 一行贡献表 + ZSet | 无事务 | 禁止把 Redis 纳入事务。结算任务自愈 |
| LeaderboardService.settleAndReconcile | ZSet 纠偏 + 标记结算 | 无事务 | 保持最终一致 |
| RuleVersionService.activate | 退役 + 晋升 + 行锁 | 已有事务 | 保持 |
| VerifyService.verify | 占位写入 + Feign + upsert + MQ + 回调 | 无事务 | 禁止把 Feign/MQ 包进本地事务 |
| VerifyService.reviewAppeal | 申诉行 + Feign + MQ | 无事务 | 跨服务。ADR 记已知残余，本变更不修 |
