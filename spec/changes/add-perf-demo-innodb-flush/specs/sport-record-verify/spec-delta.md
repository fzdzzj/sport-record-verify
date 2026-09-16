# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（演示环境可选刷盘）。

## ADDED Requirements

### Requirement: 演示环境可选刷盘

WHEN 以仓库默认方式启动中间件,
系统 SHALL 保持 InnoDB 每次事务刷盘（崩溃不丢已提交事务）。
WHEN 显式启用压测/演示 overlay,
系统 MAY 将刷盘放宽为按秒刷新，且文档 SHALL 写明最多约一秒已提交事务可能丢失；SHALL NOT 把该模式当作默认或生产配置。

#### Scenario: 默认 compose 不放宽刷盘

GIVEN 只使用仓库主 docker-compose 文件
WHEN 启动 MySQL
THEN 不启用按秒刷盘
AND 崩溃不丢失已提交事务（与 MySQL 默认耐久一致）

#### Scenario: 显式 overlay 才放宽

GIVEN 运维显式叠加压测 overlay
WHEN 启动 MySQL
THEN 刷盘可放宽为按秒
AND 文档说明这是演示取舍而非生产口径
