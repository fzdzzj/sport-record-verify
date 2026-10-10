# 提案：契约门 dotfile 盲区补认（fix-contract-dotfile-claims / TASK-197）

## 背景与问题

TASK-195 / TASK-196 两次复核均实测确认：`mailbox-contract.sh` 的 `extract_claims`（行 131）token 正则要求主名非空（`[A-Za-z0-9_./-]+\.(ext)`），任务书白名单行中裸书写的 `.trivyignore`（前导为反引号/空格，无路径字符）无法被提取，导致在途态 `--open` 判据 B 误报「改动集未声明」（rc=1）。收口无参态不受影响，但盲区会持续困扰所有触碰 dotfile 的未来课题。

## 方案（沿先例最小修复）

1. 扩展名 alternation 逐字面追加 `|trivyignore`（沿 TASK-018 `.editorconfig`、TASK-114 `.example` 先例）；提取形态：`/.trivyignore`、`xxx/.trivyignore` 可提取（前导 `/` 作主名），裸 `.trivyignore` 仍不提取（有意的约束，见下）
2. **不改**主名 `+` 为 `*`：放宽为可空主名会让正文中孤立的 `.md`/`.sh` 等被提取为垃圾 token，判据 B 若为双向匹配将引入「声明未改动」新误报——风险大于收益，预注册排除
3. 台账书写规范（随本课题写入任务书并登记项目记忆）：未来任务书 §4 / handoff 只改清单涉及 dotfile 时，以 `/.trivyignore`（带前导斜杠）或 `<路径>/.trivyignore` 形态书写

## 验收口径

- 探针三态（fixture 于 `.trae/tmp/` 用后删）：声明含 `/.trivyignore` + 基线含 `.trivyignore` 改动 → `--open` 判据 B 通过；移除声明 → 误报复现（负向对照）；常规非 dotfile 场景零回归
- 历史说明：TASK-196 任务书白名单为裸书写形态，修复后其 `--open` 回顾态仍 rc=1 属预期（书写规范约束，非脚本缺陷），不以历史变绿为验收目标；已收口任务以无参态为准
- 无参态 rc=0 保持；全量门禁（offline 569 / static / token）不因脚本改动受影响但仍按 FULL 口径留证

## 影响面与边界

- 改动面：C-01 恰 1 文件（`scripts/verify/mailbox-contract.sh` 行 131 alternation 单点追加）；C-02 恰 4 台账文件
- 零触碰：其余全部（业务代码、ci.yml、docker-compose*、pom、web、sql、`.trivyignore` 本体、既有台账内容纯追加除外）
- 本课题为 N1 根因治理，授权解除「禁触碰 scripts/verify/*」红线仅限本单点
