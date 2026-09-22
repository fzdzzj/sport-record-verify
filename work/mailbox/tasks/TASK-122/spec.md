# TASK-122 后端研发工程

## 目标
静态检查三件套（checkstyle / spotbugs / pmd）经唯一验收入口接入 CI：`mvn-verify.sh` 新增
`--static[=<模块>]` 子命令，CI build job 在镜像构建步骤后增加一步调用。项目铁律是
「验收命令拼写唯一定义处是 `mvn-verify.sh`」，CI 不得手拼 mvn 命令。

## 只改文件
- scripts/verify/mvn-verify.sh（新增 `--static[=<模块>]` 子命令，缺省 leaderboard-service）
- scripts/verify/README.md（新子命令说明）
- .github/workflows/ci.yml（build job 新增一个步骤，不动既有步骤判据）
- work/mailbox/tasks/TASK-122/spec.md、handoff.md（本两件套，新建）
- work/mailbox/PLAN.md（追加验收记录）

## 验收命令
```bash
bash scripts/verify/mvn-verify.sh --static
bash scripts/verify/mvn-verify.sh --static=leaderboard-service
```

## 完成定义
- 红绿取证：向 LeaderboardService.java 尾部注入超长注释哨兵 → `--static` rc=1（附违规原文行号）；
  `git show HEAD:<path>` 还原 + cmp 零差异 → 复跑 rc=0；`--static=nonexistent-module` → rc=2
- 全仓回归 `--mode=offline test` 用例数与开工基线逐位一致（零扰动）
- 停止边界：不把三 goal 绑进 verify 生命周期（保持「不绑 phase」）；不扩展到其余模块；
  不动 CI 既有步骤判据；不 push（CI 效果待下次 push 复验，如实标注）
