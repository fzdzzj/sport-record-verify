# TASK-118 handoff：台账禁用词原文改写（解锁下次 push）

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-106/handoff.md
- work/mailbox/tasks/TASK-118/spec.md
- work/mailbox/tasks/TASK-118/handoff.md

开工基线 `0f31c18`（`git status` 事前仅 `?? .trae/`，领先 `origin/main`（`726cf63`）5 个提交）。
未 push、未建 PR（任务硬边界）；未改 `.github/workflows/ci.yml`、未动 `spec/changes/archive/**`
与 `docs/internal/**`、未改契约脚本与统一验收入口本体；全程未用 `git stash`。

## 红取证（改前，CI 同款判据，两 locale 各一次）

脚本 `.trae/tmp/wording-check-118.sh`（UTF-8 承载禁用词正则，命令行保持纯 ASCII），
`if git grep -n -I -iE <禁用词表>` + 三排除，与 `ci.yml` 的 `Public docs wording self-check` 同构。

```
===== locale=C rc=0 =====
work/mailbox/PLAN.md:353:| 词面自检 | ...（含禁用词原文的那一行）
work/mailbox/tasks/TASK-106/handoff.md:97:  ...（含禁用词原文的那一行）
VERDICT[C]=HIT-FOUND hits=2
===== locale=default rc=0 =====
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17: ...
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:36: ...
work/mailbox/PLAN.md:353: ...
work/mailbox/tasks/TASK-106/handoff.md:97: ...
VERDICT[default]=HIT-FOUND hits=4
OVERALL_RC=1  (0=CI step would pass / 1=CI step would fail / 2=git error)   ← 实测退出码 1
```

台账两行在**两种 locale 下都命中**；默认 locale 另有 2 条落在 `MapMatchResultDTO.java`
（本任务只改清单外），成因是同源的 locale 伪影（见下节）。
