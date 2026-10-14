# TASK-226: LIGHT 清理批（H3，P6）

派发基线：TASK-225 已封盘（origin=5954cad），封盘笔 bc84594 在本地随本课题批次推送（勿动）。工作树干净；offline 基线 52/50/173/250/159/80/26；token 基线 SUM=2036。提案三件套=spec/changes/light-cleanup-batch/。

## S0 红线

零功能/测试逻辑变更；不触 api/pom/ci.yml/scripts/compose/sql；仅允许：RecordControllerTest.java（恰补末尾换行，保 CRLF）、README 文档、台账文件、BACKLOG。命令行禁中文；MSYS sed -i 禁 tracked 文件。

## S2 预注册清单

1. record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java：补末尾换行（TASK-221 留痕-1 清偿；除末字节 0x0A 外零 diff）。
2. README 订正（recon 定清单）：web/README.md 与根 README 陈旧表述（如功能列表缺新功能/旧口径描述）——仅文档订正，逐处留对照。
3. 静态基线偏差台账化：--static=user-service / api 的既有违规清单登记入 handoff（不修码）。
4. BACKLOG.md 推荐序行刷新（F 系已清空）。

## S3 停止条件

1. recon 发现需要修码才能解决的项（静态违规修复超范围）→ 只登记不动手。
2. README 订正涉及行为承诺变更 → 停止上报。
3. offline record-service 任何用例变化（应为逐位 250 不变）。

## S4 白名单（预估 4-6 文件）

上述 1-4 项文件 + 台账 4 文件（C-02 沿 LIGHT 惯例可并为单笔，由执行侧定，逐字符全路径登记）。

## S6 门禁（LIGHT）

offline --pl record-service（单模块收窄合法：仅测试文件行尾+文档，零 api）期望逐位 250 不变；diff --check rc=0；token 2036 持平；词面门 ZERO_HIT；契约门收口无参 rc=0；行尾核对（RecordControllerTest 仍 CRLF + 末尾 0x0A）。LIGHT 档免独立复核（沿 TASK-212 先例），指导亲核后随 TASK-227 批次推送。
