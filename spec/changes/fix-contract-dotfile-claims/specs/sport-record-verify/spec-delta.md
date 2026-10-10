# spec-delta：契约门 dotfile 盲区补认（TASK-197 fix-contract-dotfile-claims）

## MODIFIED 需求：extract_claims token 提取正则

`scripts/verify/mailbox-contract.sh` 行 131 的扩展名 alternation SHALL 追加 `trivyignore` 字面条目（沿 `.editorconfig`（TASK-018）/ `.example`（TASK-114）先例），主名字符类 `[A-Za-z0-9_./-]+` 与其余 alternation 零改动。效果：`/.trivyignore`、`<路径>/.trivyignore` 形态的声明 token 可被提取；裸 `.trivyignore`（无前导路径字符）保持不提取（有意约束：避免主名放宽为 `*` 后孤立 `.md`/`.sh` 垃圾 token 进入声明集、在双向匹配判据 B 下引入「声明未改动」新误报）。

## ADDED 需求：dotfile 只改清单书写规范

未来任务书 §4 / handoff 只改清单涉及 dotfile 时 SHALL 以 `/.trivyignore`（前导斜杠）或 `<路径>/.trivyignore` 形态书写，确保契约门在途态判据 B 可提取。

## 验收断言

- 探针三态：正向（声明 `/.trivyignore` + 改动 `.trivyignore` → 判据 B 通过）/ 负向（移除声明 → 误报复现）/ 回归（常规 fixture 提取逐 token 与修复前一致）
- 无参契约门 rc=0；`--open TASK-197 --baseline=<派发笔>` 在途态 rc=0（真实链路）
- 历史 `--open TASK-196` 回顾态预期仍 rc=1（其任务书为裸书写，属书写规范约束非脚本缺陷），登记不改判
- 只改清单：C-01 恰 1 文件（mailbox-contract.sh）；C-02 恰 4 台账文件；offline 569 逐位 + static 双模块 rc=0 + token 只增不减
