# TASK-248 台账（handoff）

> **TASK**：B23 覆盖增强——好友榜 15s 本地缓存「空榜不缓存防背锅」机制补齐显式单测断言（MEDIUM 档）  
> **开工基线**：`770fc40`（main，工作树干净）
> **方案**：BACKLOG 推荐序第 1 位 B23 覆盖增强  
> **状态**：已实施

## 1. 实施摘要

判别式 R1（核心防背锅）与 R2（无好友同口径）均已在 `LeaderboardCacheInvocationTest` 补齐并通过：

- **R1** `emptyBoardFromDegradation_isNotCached_secondCallRefreshesOnRecovery`：`userApi.listFriends(7L,1L,1000L)` 首次 `thenThrow` → 第一次 `top("friend",7L,10)` 断空榜；按 `LeaderboardServiceTest` L380 先例用 `doReturn(...).when(...)` 重打桩（恢复 1 好友页 + Pipeline `[42.5]`）→ 第二次调用断非空；`verify(userApi, times(2)).listFriends(7L,1L,1000L)` 证明空榜未进缓存、两次均真实穿透 Feign。
- **R2** `emptyBoardFromNoFriends_isNotCached_secondCallPenetratesAgain`：`listFriends(8L,1L,1000L)` 返回空页（total=0），连续两次 `top("friend",8L,10)` 均空，`verify(userApi, times(2))` 证明无好友空榜同口径不缓存。

均复用 `CachingTestConfiguration` Spring 代理入口（`AopUtils.isAopProxy` 既有判别式口径），行为断言优先，未用反射直取私有缓存字段，未删弱/改写任何既有断言，零生产代码改动。

## 2. 修改清单（实际改动集）

实际改动集 = `git diff --name-only 770fc40` + 未跟踪文件（`.trae/*` 已按契约排除），恰为 spec.md §2 的 3 文件白名单，全等声明：

- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`（仅新增 2 个测试方法）
- `work/mailbox/tasks/TASK-248/spec.md`（替换开工基线占位符 `<立项笔哈希>` 为 `770fc40`）
- `work/mailbox/tasks/TASK-248/handoff.md`（本台账回填）

除上述 3 文件外无任何其他改动（含 src/main 生产代码、其余测试文件、pom/package.json 均零触碰，违反即红线）。

## 3. 门禁验证记录

1. **后端单测**（离线分级收窄）：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service test`
   → 退出码 **0**，BUILD SUCCESS。`LeaderboardCacheInvocationTest` **Tests run: 5**（原 3 + 新增 2），Failures=0 / Errors=0 / Skipped=0；模块累计 `Tests run: 94, Failures: 0, Errors: 0, Skipped: 0`，测试数只增不减；common 40 / api `-` / leaderboard 94。
2. **前端回归**（零前端改动，照跑）：`cd web; pnpm run type-check` 通过；`pnpm test` → **4 文件 61 tests 全 pass**，退出码 0。
3. **契约门**（Git Bash）：`bash scripts/verify/mailbox-contract.sh --open=TASK-248 --baseline=770fc40` → 退出码 **0**（判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致）。
4. **token 门**（29 项 tracked 全量 `git grep -cF` SUM，权威口径）：基线 `770fc40` = **2011**，当前工作树（收口态）= **2011**，逐项持平、只增不减；改动集 3 文件对 29 项受保护 token 字面量 **零命中**（清单经 `.trae/tmp/tokenlist_T219.txt` 29 行逐项复算，不枚举字面量以免全仓计数自增失准）。
5. **词面门**（CI 权威口径 `git grep -n -I -iE "...`，本机显式 `-E`）：
   - 真实扫描（排除 spec/changes/archive、docs/internal、本 ci.yml）**零命中**（grepp 无匹配 rc=1）；
   - **探针阳性对照**：对自含禁用词的 `.github/workflows/ci.yml` 显式 `-E` 扫描命中 1 行 rc=0 → 正则引擎有效、探针阳性成立；
   - **探针阴性对照**：absent 词零命中 rc=1。
   词面门通过。
6. **`git diff --check`**：C-01 提交前 **rc=0 净**（无空白错误；LF→CRLF 为 git 换行转换提示，非空白违规）。

## 4. 已知受限与偏差声明

- **R1 pipeline 计数断言按实际执行路径校正**：spec §3 R1 原写 `verify(redis, times(2)).executePipelined(...)`。实测执行路径为：首次 `thenThrow` 时异常发生在 `loadTopFriendsWithoutCache`→`fetchAllFriendIds` 第一步（listFriends 调用处），被 `catch` 捕获后直接返回空榜，**不会触达 `executePipelined`**；仅恢复路径（第二次）执行一次 pipeline。故 pipeline 断言按实际路径写为 `verify(redis, times(1)).executePipelined(any(RedisCallback.class))`，并在 Javadoc 注释说明（首次降级未触达 Pipeline）；好友列表 Feign 计数保持 spec 原意 `verify(userApi, times(2)).listFriends(7L,1L,1000L)`（两次均真实穿透，空榜未进缓存，核心防背锅语义成立——若误去 `isEmpty` 判定，第二次将命中缓存，`userApi` 只调 1 次，该断言失败）。此校正已与用户确认（选择「改为 times(1)」）。
- 其余无受限项：未触碰生产代码、未删弱既有断言、未用反射直取私有缓存字段、未 push、未建 PR、未触碰 PLAN.md/HANDOVER.md。

## 5. 双提交记录

- **C-01 实施笔** `1ed6f57`（`1ed6f5705e51f23a81e2c33d6b6f4cd751cd99d6`）：
  `test(leaderboard): 补齐好友榜空榜不缓存显式断言（TASK-248）` — 仅文件 1（`LeaderboardCacheInvocationTest.java`，58 行新增）。
- **C-02 台账笔**（收口最终 HEAD）：
  `docs(mailbox): 登记TASK-248实施完成与门禁回填（TASK-248）` — 仅文件 2（spec.md）与文件 3（handoff.md）。C-02 台账笔经 `--amend` 归并；最终两笔提交为 **C-01 实施 `1ed6f57`** 与 **C-02 台账（HEAD，见 `git log -1`）**，工作树于收口日净（`git status --short` 为空）。