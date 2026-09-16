# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（提交事件异步发布）。

## ADDED Requirements

### Requirement: 提交事件异步发布

WHEN 运动记录提交事务已提交,
系统 SHALL 异步发布 SUBMITTED 事件，SHALL NOT 在提交请求线程上同步等待消息中间件往返。
发布失败时系统 SHALL 仍走既有降级（直调校验或转人工），SHALL NOT 把已落库记录当成提交失败。

#### Scenario: 发送不阻塞提交线程

GIVEN 记录已落库且事务已提交
WHEN 发布 SUBMITTED 事件
THEN 提交请求路径不等待同步发送完成
AND 校验仍由事件或降级路径触发

#### Scenario: 异步发送失败仍降级

GIVEN SUBMITTED 异步发送失败
WHEN 失败回调发生
THEN 系统走直调校验或转人工
AND 不回滚已提交的记录
