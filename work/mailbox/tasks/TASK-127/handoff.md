# TASK-127 回传：归档三个 spec 变更并收敛两处 /actuator/** 漂移

## 结论

两处漂移按判别式收敛（②红绿取证 + 变异验证，①纯规格文本并档前修正），三个变更归档并入
主规格「鉴权」域（EARS/Scenario 原文逐字搬移），收口后 `spec/changes/` 下 0 个在途变更。
全量 offline 绿：`20/30/33/80/81/50/6 = 300`（gateway +1，其余六模块与基线 299 逐位一致）。

## 只改清单

- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java
- gateway-service/src/test/java/com/sportverify/gateway/auth/ActuatorWhitelistNarrowTest.java
- spec/specs/sport-record-verify/spec.md
- spec/changes/archive/add-auth-degrade-header-strip/proposal.md
- spec/changes/archive/add-auth-degrade-header-strip/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/add-auth-degrade-header-strip/tasks.json
- spec/changes/archive/narrow-actuator-exposure/proposal.md
- spec/changes/archive/narrow-actuator-exposure/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/narrow-actuator-exposure/tasks.json
- spec/changes/archive/add-strict-secret-fail-fast/proposal.md
- spec/changes/archive/add-strict-secret-fail-fast/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/add-strict-secret-fail-fast/tasks.json
- work/mailbox/tasks/TASK-127/spec.md
- work/mailbox/tasks/TASK-127/handoff.md
- work/mailbox/PLAN.md

## 落点与移动说明

三个变更目录经 `git mv` 整目录移入 `spec/changes/archive/`（重命名识别，上面按落点新路径
列出）；其中提案文件三份内容零改动（纯移动），差异文件三份中仅 add-auth-degrade-header-strip
一份有漂移①修正 + 头部「不归档」说明更新，任务清单文件三份各追加一个归档阶段（已 completed）。

## 漂移②红绿取证

判别式：ApplicationContextRunner 不注入任何 whitelist 属性实例化 AuthGlobalFilter（注册
JwtTokenParser bean + 名为 conversionService 的 ApplicationConversionService bean 复刻 Boot
生产语义——裸 runner 对 @Value 的 List 不按逗号拆分，首跑红证实测注入为单元素整串，已在对齐
后以最终形态重取红），断言生效白名单等于新默认值 `/api/auth/**,/actuator/health` 及三条行为
判别（health 放行 / metrics 不放行 / login 放行）。测试落在既有 ActuatorWhitelistNarrowTest
（29→30）。

- 红（改前，默认值仍为旧整段通配）：
  `bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test`
  → rc=1，`Tests run: 30, Failures: 1`，唯一红即新判别式：
  `ActuatorWhitelistNarrowTest.defaultWhitelistEqualsHealthProbeOnly:133->lambda:138`
  [@Value 默认白名单必须是 /api/auth/**,/actuator/health（健康探针精确匹配）]
  `but was: ["/api/auth/**", "/actuator/**"]`（日志 .trae/tmp/task127-red3.log）。
  中间轮注记（如实登记）：初版判别式（裸 runner）红在但期望形态与生产转换语义不一致
  （task127-red.log / task127-red2.log），对齐后重取红如上。
- 绿（@Value 默认值改为 `/api/auth/**,/actuator/health` + 类注释白名单一行同步）：
  同命令 → rc=0，`Tests run: 30, Failures: 0` / BUILD SUCCESS（task127-green.log）。
- 变异验证（TASK-106/125 手法，cp + sha256，全程未用 git stash）：修复态 cp 留底
  （sha256 `8a415b32…` AuthGlobalFilter.java / `6659a251…` ActuatorWhitelistNarrowTest.java，
  存 .trae/tmp/task127-fixed.sha256）→ sed 临时还原旧默认值 → 同命令复红 rc=1、
  同一判别式 `but was: ["/api/auth/**", "/actuator/**"]`（task127-mutation.log）→ 字节级
  cp 还原 → `sha256sum -c` 两文件 OK + cmp IDENTICAL。
- javadoc 部分（类注释白名单行）无机械红，随实现同 commit，以全量离线零扰动 + 词面收口背书。

## 漂移①

add-auth-degrade-header-strip 的 spec-delta「白名单路径下的伪造头清洗」GIVEN 文本
`/api/auth/**` 或整段通配 → `/api/auth/**` 或 `/actuator/health`（TASK-125 登记的并档前修正）；
同文件头部「本次不归档」说明同步为已归档事实描述（该句描述归档状态，非需求原文）。
需求原文（WHEN/SHALL 与三个 Scenario 语义）零改写。

## 归档与主规格

- MODIFIED「网关统一鉴权」：白名单放行场景改为新口径 + 新增「actuator 非 health 端点走鉴权」
  场景（delta 原文逐字并入，替换 spec.md 原场景段）。
- MODIFIED「白名单收紧」：需求正文 + 5 个场景按 delta 原文逐字替换（原「生产 actuator 收敛
  口径已文档化」场景被 delta 的「白名单无 actuator 整段通配」「health 探针不泄组件明细」
  替代，属 MODIFIED 语义内的原文搬移，非顺手改写）。
- ADDED「网关降级路径剥离身份头」插入「鉴权降级开关」之后；ADDED「密钥注入严格模式」
  「内部接口密钥常量时间比较」插入「内部接口共享密钥校验」之后。
- 头部「本规范已归档以下提案」追加三条；「变更历史」按先例补三条融合备注条目。
- tasks.json 三个各追加「归档」阶段（步骤全 completed）；`git mv` 后 `spec/changes/` 下
  仅剩 archive/（在途 0 个）。

## 全量与用例数

`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS，
模块合计 `20/30/33/80/81/50/6 = 300`（Failures 0 / Errors 0 / Skipped 0）。基线 299
（20/29/33/80/81/50/6）：gateway 29→30（+1 判别式），其余六模块逐位一致零扰动。
生效模式 offline、依赖来源可判定（未触发退出码 3）。日志 .trae/tmp/task127-full.log。

## 契约与词面

- 契约（在途）：`bash scripts/verify/mailbox-contract.sh --baseline=415d36d` →
  TASK-127 判据 A 两件套齐全、判据 B 通过（只改清单 15 项与实际改动集逐项一致），
  结果回填本文件与 PLAN.md（实测为准）。
- 契约（收口后）：收口提交后无参数复跑，退出码 0（实测回填）。
- 词面自检：CI 同款正则（全部 tracked 文本载体）、`LC_ALL=C` 与默认 locale 双跑，
  判据以 LC_ALL=C 为准；默认 locale 仅余 TASK-118 起登记的 2 条本机伪影
  （api 模块 DTO 两行，本任务未触碰）。

## 提交

分批 conventional commits：① fix(gateway) 漂移②（默认值+javadoc+判别式，commit 内绿）；
② docs(spec) 漂移① + 三变更归档并入；③ docs(mailbox) 台账两件套 + PLAN 验收记录。
未 push（任务硬边界），待下次 push 由 CI 复验。

## 未决

1. 本次改动未 push，CI 复验待下次 push（外部门槛不作声称）。
2. 默认 locale 词面伪影 2 条（api 模块 DTO，清单外既有登记，LC_ALL=C 口径零命中）。
3. 契约/全量在收口提交后的最终实测数字以 PLAN.md 验收记录回填为准。
