# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（JVM 停顿目标）。

## ADDED Requirements

### Requirement: JVM 停顿目标可选

WHEN 以推荐方式启动业务服务,
系统 SHALL 允许设置 G1 停顿目标（50ms 量级），SHALL NOT 把固定大堆当作推荐配置。

#### Scenario: 推荐不含固定大堆

GIVEN 阅读启动说明或脚本
WHEN 采用推荐 JVM 参数
THEN 可包含停顿目标
AND 不包含已否决的固定 1g 堆作为推荐项

#### Scenario: 固定大堆不被默认启用

GIVEN 未显式传入堆大小
WHEN 启动服务
THEN 不因本变更而设置 -Xms/-Xmx 为 1g
