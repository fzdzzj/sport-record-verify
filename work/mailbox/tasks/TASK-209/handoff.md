# TASK-209 回传交接（日期范围榜单：周榜 / 月榜视图）

## 1. 编号、基线与外部状态

- 课题：add-leaderboard-range（按 settled_at 半开窗口聚合日期范围榜单，前端日/周/月视图）。
- 分级：**FULL**（核心查询逻辑；NEW 4-block 复核：Git 链 + only-changed-list 合并 / 红线抽查 / 门禁复跑 / 证据链一致性）。
- 开工基线：`44edabc`（派发笔，含提案三件套 proposal/spec-delta/tasks.json 与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- 未 push、未建 PR；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 恰 8 文件；C-02 恰 4 台账文件；两笔合计 12 文件 = 从 `44edabc` 的改动集）

C-01（feat(leaderboard) 实施笔 `8b1a23b`）：

- README.md
- leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/controller/LeaderboardControllerTest.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- web/src/api/client.ts
- web/src/pages/leaderboard.page.vue

C-02（docs(mailbox) 台账笔，本 handoff 所在提交）：

- spec/changes/add-leaderboard-range/tasks.json（勾选）
- work/mailbox/tasks/TASK-209/spec.md（§7 回填）
- work/mailbox/tasks/TASK-209/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

## 3. 一句话结论

日期范围榜单落地：`GET /api/leaderboard/range?start=&end=&size=` 对贡献表
`leaderboard_contribution` 按 `status=ACTIVE` 且 `settled_at` 半开窗口
`[start 0:00, end+1 日 0:00)` 做 `SUM(distance) GROUP BY user_id` 降序单查询聚合；
service `rangeReport` 默认 50 / 上限 500、rank 从 1、昵称按 dailyReport 同路径补齐；
controller 校验 start 必填 / end 缺省今天 / start>end 400 / 跨度>366 400；web 增
今日/本周/本月 tab（周=ISO 周一、月=本月 1 号、end=今天）；README 补 range 口径。
全部 §2.5 判别式红绿 + §6 门禁按 FULL 档留证。

## 4. 判别式红绿（LeaderboardServiceTest +5 / LeaderboardControllerTest +5，纯 JVM Mockito）

- **R1a（绿）**：多用户多行 ACTIVE 贡献入窗 → SUM 降序、rank 从 1、昵称补齐（getNickname/rank/顺序断言）。
- **R1b（绿）**：半开边界——`start.atStartOfDay()` 计入；`end.plusDays(1).atStartOfDay()` 为排他上界（verify selectRangeSummaries 参数逐值）。
- **R1c（绿）**：非 ACTIVE（ROLLED_BACK）不入窗——状态过滤全交给 Mapper（SQL status=ACTIVE），服务端聚合只含 Mapper 返回的 ACTIVE 汇总。
- **R1d（绿）**：start>end → IllegalArgumentException（GlobalExceptionHandler 400 PARAM）；跨度>366 天（start.plusDays(366) 早于 end）→ 400；start 声明为 `@RequestParam(required=true)`（反射断言），缺失由 Spring 映射 400；跨度恰 366 天边界放行。
- **R1e（绿）**：size<=0 → 默认 50；size>500 → 收敛 500（与 dailyReport 同口径）；空窗返回空列表。
- **G1（绿）**：既有 top/dailyReport/friend/settleAndReconcile 等全部既有用例零回归（leaderboard-service 80/0/0/0）。
- **G2（绿/核空）**：offline 全量 675 = 52/50/162/157/159/80/15，仅 leaderboard 70→80 净增 +10，其余六模块逐位不变；写入链（applyVerified/revert/补偿）、定时结算、ZSet 键、索引、sql/**、api/**、gateway/common/其他服务/父 pom/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose 均 diff 核空。

## 5. §6 门禁留证

1. **offline 全量逐位**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` →
   **BUILD SUCCESS rc=0**（脚本本体为权威），逐位 **675 = 52/50/162/157/159/80/15**
   （common/gateway/user/record/verify/mapmatch 六模块逐位不变；仅 leaderboard 70→80 净增 +10，只增不减）。
2. **静态门**：`--mode=offline --static=leaderboard-service` rc=**0**，Checkstyle 0 violations、SpotBugs Error size 0、PMD success（S3 无新静态违规）。
3. **契约门双态**：在途 `--open TASK-209 --baseline=44edabc` rc=**0**（判据 A 两件套齐含 1 个待办进行中 = TASK-209 仅 spec 列待办放行；判据 B 一致）；收口无参 rc=**0**。
4. **token 29 项 2030**：见 §6（开工/post-C01 2030 + close 2030，只增不减）；本课题新文档未引入受保护 token 字面量。
5. **词面门**：CI-exact 正则（自 ci.yml 提取的敏感词表，经 `.trae/tmp/wc-pattern.txt` 注入）`git grep -n -I -iE -f <pattern>` 对改动文件集 ZERO_HIT rc=**1**；合成探针阳性对照（对同一词表 grep 含词样文）HIT rc=**0**，证明 -E/-i 口径有效、无假阴性。
6. **`git diff --check`/`--cached --check`** 均 rc=**0**；两笔 `git show --check` rc=**0**。
7. **行尾**：C-01 改文件保持基线各自身（LeaderboardController.java/LeaderboardServiceTest.java/README.md 为 CRLF；LeaderboardService.java/LeaderboardContributionMapper.java/leaderboard.page.vue/client.ts 为 LF）；新文件 LeaderboardControllerTest.java / handoff.md 纯 LF + 末尾换行、无 BOM。
8. **web**：本机 `pnpm run build` 因 esbuild 清理 Windows temp 目录 `Access is denied` 受阻（环境/toolchain 面，非代码错；3283 模块已 transform 后仅 esbuild 临时目录清理失败）；fallback `pnpm run type-check`（vue-tsc --noEmit）**rc=0**，登记 CI web job 为权威（TASK-205/208 D-esbuild 先例）。

## 6. token 时点读数

- token 29 项 tracked `git grep -cF` 全量 SUM：开工（44edabc）=**2030**、post-C01=**2030**、close=**2030**（只增不减；新文档零 token 字面量）。

## 7. 异常与偏差登记

- **D1（台账笔自指哈希）**：本 handoff 无法记录 C-02 自身哈希，沿 TASK-178/183/204/205/206/207/208 同型先例，以 C-02 实际提交哈希为准（`git log --oneline`）。
- **D2（web build 本机受阻）**：本地 `pnpm run build` 因 esbuild 在 Windows 清理临时目录报 `Access is denied` 退出 1（环境面，非代码错）；改走 TASK-205/208 D-esbuild 先例——`vue-tsc` type-check rc=0，登记 CI web job 为权威构建口径。
- **D3（实现细节两处）**：① `@RequestParam("start")` 必填以反射断言（纯单测不起 Spring 容器，Spring 缺失参数 400 分支由 `@RequestParam(required=true)` 声明在位覆盖）；② Mapper `@Select` 注解 SQL 中的 `<`/`>` 比较符直接书写不转义（Java 注解字符串非 XML，与既有 `@Select` 一致），javadoc 内用 `&lt;`/`&gt;` 保持文档可读。
- **停止条件（§3）**：无一条触发——白名单无越界、mapper/查询范式无冲突、无新静态违规、offline 基线其余六模块逐位不变。

## 8. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**；token close=**2030**；`git diff --check` rc=0；`git diff --name-only 44edabc` 对照恰 **12 文件**（C-01 8 + C-02 4），对 api 模块/gateway/common/其他服务/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/` 排除）。

## 9. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、真实跨服务/DB 聚合链路未跑；未 push、未建 PR，无 CI run，外部终验待指导侧推动推送后补记。
- **无性能/吞吐声明**：settled_at 无专用索引（已登记已知限制，全局禁加索引，见任务书 S0），范围扫描演示规模可接受，生产需独立 perf 课题引入；未对范围查询作任何吞吐/延迟承诺。
- 日期范围榜为公共聚合面（同 /daily，无身份隔离），属产品既有口径，POST /daily 同款暴露面。
