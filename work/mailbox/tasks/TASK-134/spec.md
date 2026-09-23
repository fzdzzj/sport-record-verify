# TASK-134 好友榜读取修复：分页取齐好友 + 有界分批扫描

## 目标

仅处理 `leaderboard-service` 的好友榜读取：

1. 核对并遵守 `UserApi.listFriends(userId, page, size)` 的 `PageResult(current, size, total, records)` 分页契约，修复当前只读第 1 页导致好友超过 1000 人时的静默遗漏；
2. 将好友榜对 `leaderboard:overall` 的整榜 `reverseRangeWithScores(0, -1)` 改为固定窗口分批扫描，命中请求 `size` 后停止，榜尾仍可继续扫描到结束；
3. 保持现有语义：只显示好友（排除本人和非好友）、按 ZSet 分数降序、`rank` 为过滤后序号、好友服务失败/降级返回空榜、榜单无命中返回空榜；
4. 本任务**不添加 `@Cacheable`**，不改变总榜缓存，不改好友分页 API、user-service、api 契约或其他业务服务。

## 已核对的契约与设计

- `api/.../UserApi.java` 的好友列表接口接收 `page`/`size`，返回 `Result<PageResult<FriendDTO>>`。
- `user-service/.../FriendService.java` 返回 `PageResult` 的 `current`、`size`、`total`、`records`；好友接口当前调用大小沿用 1000。
- `leaderboard-service` 读取第一页后，按 `total` 和返回记录继续读取后续页；异常、空数据或不可用结果仍沿现有 catch 口径返回空榜。
- Redis 扫描批大小固定为 500；每批最多取 `[start, start + 499]`，按批内原有降序过滤；累计到 `size` 立即组装并返回，否则直到短批/空批结束。
- 不自行引入缓存失效、TTL 或好友关系一致性产品决策；本任务只改读取完整性与单次 Redis 读取上界。

## 回归判别式

- 旧行为下，`total=1001` 且第 1001 个好友在第 2 页时只读第一页，好友榜遗漏该好友；新行为必须读取第 2 页并返回该好友。
- 旧行为下，好友在榜单第 1001 位时整榜 mock 只覆盖 `0,-1`，新行为必须以 `(0,499)`、`(500,999)`、`(1000,1499)` 有界窗口命中，且返回 `rank=1`。
- 既有只显示好友、空榜/Feign 降级、排序和 rank 用例继续通过。

## 只改清单

- `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
- `work/mailbox/tasks/TASK-134/spec.md`
- `work/mailbox/tasks/TASK-134/handoff.md`
- `work/mailbox/PLAN.md`

## 停止边界

- 不添加 `@Cacheable`。
- 不修改 `api`、`user-service` 或其他业务服务，不修改好友分页接口含义。
- 不修改 `.trae/`，不 push，不建 PR。
- 若发现 `total`/分页页码语义与现有实现不一致，或缓存/好友关系变化需要产品决定，停止并回报，不自行猜测。

## 验收入口

按仓库入口运行目标模块：

```text
bash scripts/verify/mvn-verify.sh --mode=online --pl leaderboard-service test
```

必要时补目标模块静态入口；真实 Redis/MySQL/RocketMQ IT 不因本任务新增，缺少环境时按“未覆盖”记录。
