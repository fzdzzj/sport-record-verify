# TASK-230: 删除记录联动回撤榜单里程贡献（F5 候选，TASK-223 D1 挂账，MEDIUM 档）

> **开工基线**：cb865be（工作树干净）
> **任务级别**：MEDIUM

## 1. 目标与背景

- **背景**：TASK-223（记录删除/撤回）上线后，用户删除运动记录时，`record-service` 级联删除了主记录、轨迹分片表与点赞表，但未联动回撤 `leaderboard-service` 自有持久表（`leaderboard_contribution`）与 Redis ZSet 中的里程积分，挂账为已知偏差 D1 / BACKLOG F5。
- **目标**：打通跨服务联动，在用户删除记录时，通知 `leaderboard-service` 将对应贡献状态更新为 `ROLLED_BACK` 并精确扣回 Redis ZSet 榜单里程。

## 2. 修改白名单

严格限定在以下 6 个文件（+台账文件）：
1. `api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApi.java`
2. `api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApiFallback.java`
3. `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/InternalLeaderboardController.java`
4. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
5. `leaderboard-service/src/test/java/com/sportverify/leaderboard/controller/InternalLeaderboardControllerTest.java`
6. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
7. 台账文件：`work/mailbox/tasks/TASK-230/spec.md`、`work/mailbox/tasks/TASK-230/handoff.md`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`

## 3. 核心设计与红线要求

- **契约与实现**：`LeaderboardApi` 新增 `POST /internal/leaderboard/records/{recordId}/rollback`，`InternalLeaderboardController` 承接既有 `leaderboardService.rollbackOnRejected(recordId)`。
- **最终一致性（ADR-0009）**：`SportRecordService.deleteRecord` 在事务 `afterCommit`（及无事务上下文分支）内以 best-effort 方式调用回撤，捕获所有异常并打 warn 日志留证，绝不导致已提交的删除事务回滚，杜绝跨库分布式事务。
- **Checkstyle 家法**：`SportRecordService` 构造器已有 7 参，`LeaderboardApi` 必须采用 `@Autowired(required = false)` 的 setter 注入，构造方法保持 7 参，严格不超 8 参数上限。
- **复用既有状态机**：复用 `LeaderboardService` 现有的 `lock:rollback:{recordId}` 分布式互斥锁与 `ACTIVE → ROLLED_BACK` 乐观锁状态迁移逻辑，保证回滚幂等且无贡献不产生负里程。

## 4. 验证命令

- 离线全量模块单测：`bash scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service,record-service test`
- 静态检查：`bash scripts/verify/mvn-verify.sh --static=leaderboard-service` 与 `--static=record-service`
- 台账契约：`bash scripts/verify/mailbox-contract.sh`
