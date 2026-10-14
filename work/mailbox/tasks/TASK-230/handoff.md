# TASK-230 handoff：删除记录联动回撤榜单里程贡献（F5 候选，TASK-223 D1 挂账，MEDIUM 档）

## 1. 编号、基线与外部状态

- 课题：TASK-230
- 任务书：`work/mailbox/tasks/TASK-230/spec.md`
- 开工基线：`cb865be`
- 实施 commit id：`4bae3fe — feat(leaderboard): 删除记录联动回撤榜单里程贡献（TASK-223 D1）`（6 files / +307 −8）
- 级别：MEDIUM 档

## 2. 改动清单

- api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApi.java
- api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApiFallback.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/InternalLeaderboardController.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/controller/InternalLeaderboardControllerTest.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- work/mailbox/tasks/TASK-230/spec.md
- work/mailbox/tasks/TASK-230/handoff.md
- work/mailbox/PLAN.md
- work/mailbox/BACKLOG.md
- work/mailbox/HANDOVER.md

## 3. 实现要点与边界守则

1. **LeaderboardApi / Fallback**：
   - 新增 `@PostMapping("/leaderboard/records/{recordId}/rollback")` 接口声明；
   - `LeaderboardApiFallback` 由 lambda 改为具名匿名实现，写路径 fallback 显式抛 `4008`，不静默假装成功，保证残留 ACTIVE 锚点可被对账发现。
2. **InternalLeaderboardController**：
   - 新增 POST 端点，承接既有 `leaderboardService.rollbackOnRejected(recordId)`，返回 `Result.success()`；
   - 纯复用既有乐观锁状态机与 `lock:rollback:{recordId}` 分布式锁，零改动底层逻辑。
3. **SportRecordService**：
   - 新增 `LeaderboardApi` 字段与 `@Autowired` setter，构造方法维持原 7 参数，严格遵守 Checkstyle 最多 8 参家法规约；
   - `deleteRecord` 事务 `afterCommit` 回调内 best-effort 调用，异常吞并只打 WARN 日志留证，绝不因榜单服务暂时不可用而导致已提交的删除事务回滚；无事务上下文时进入 else 直接调用。
4. **单元测试矩阵**：
   - `InternalLeaderboardControllerTest`：新建 5 个测试用例，覆盖契约声明反射、透传、幂等跳过、锁超时透传、查询端点回归；
   - `SportRecordServiceTest`：新增 5 个测试用例，覆盖 `afterCommit` 前零调用、提交后调用、4008 异常吞并、运行时异常吞并、无同步上下文直调，既有负向用例追加 `verifyNoInteractions(leaderboardApi)`。

## 4. 验证结果（指导主 Agent 独立复核）

- **测试重跑**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service,record-service test`
  - common: 52 pass
  - record-service: 255 pass
  - leaderboard-service: 85 pass
  - BUILD SUCCESS（exit code 0）。
- **静态检查**：`--static=leaderboard-service` 与 `--static=record-service` 均通过，0 Checkstyle 违规。
- **契约校验**：`bash scripts/verify/mailbox-contract.sh` 退出码 0。

## 5. 遗留风险与未覆盖点

1. **回撤失败无自动收敛**：删除已提交后若 Feign 调用或获取锁失败，贡献锚点残留 ACTIVE、ZSet 里程不扣，导致榜单里程轻微虚高；当前靠 WARN 日志人工排查，建议后续可安排定时对账扫描（检测 `sport_record` 缺失且锚点 ACTIVE 的异常行）。
2. **fallback 无独立测试文件**：由于白名单未包含 api 模块测试目录，4008 降级路径通过代码审查与 `SportRecordServiceTest` 中「BizException 4008 被吞并」用例间接验证。
3. **真实中间件与全栈联调**：当前为单测 Mockito 验证，真实 Redis ZSet 扣分与真实网关鉴权放行留待后续集成环境演练。
