# Sport Record Verify 变更规格：harden-surefire-agent-attach

## MODIFIED Requirements

### Requirement: surefire 测试 JVM 的 agent attach 确定性

多模块构建的 surefire 测试 JVM SHALL 以 `-Djdk.attach.allowAttachSelf=true` 启动，且 SHALL 保留既有 JaCoCo agent 注入（`@{argLine}` 晚绑定），使 Mockito inline mock maker 的 ByteBuddy agent 挂载走进程内确定性路径。

#### Scenario: argLine 组合保留

- WHEN JaCoCo prepare-agent 注入 `argLine` property
- THEN surefire 实际 argLine SHALL 同时包含 JaCoCo agent 路径与 `-Djdk.attach.allowAttachSelf=true`，二者 SHALL NOT 互相覆盖。

#### Scenario: 离线基线不变

- WHEN 本地 offline 全模块测试执行
- THEN 测试总数 SHALL 恒为 450（36/41/33/127/144/59/10），Failures/Errors/Skipped 全 0。

#### Scenario: 外部终验

- WHEN 修复随推送触发外部门槛 CI
- THEN CI SHALL 全绿达成门槛；若同签名失败再现，SHALL 转入深诊断并如实登记（不以再重试刷绿）。
