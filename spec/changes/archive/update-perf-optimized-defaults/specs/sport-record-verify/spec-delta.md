# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（提交路径默认走已验证的批量插入）。

## ADDED Requirements

### Requirement: 提交路径默认批量插入

WHEN 服务以默认配置提交含轨迹点的运动记录,
系统 SHALL 使用单分片批量插入轨迹点，SHALL NOT 默认逐条插入。
系统 SHALL 保留可关闭的开关，以便复现逐条插入的基线。

#### Scenario: 默认配置走批量

GIVEN 未显式关闭批量插入开关
WHEN 提交一条含多轨迹点的新记录
THEN 轨迹点以批量插入写入
AND 不对该记录的每个点单独执行一次插入

#### Scenario: 显式关闭后可复现基线

GIVEN 批量插入开关被显式关闭
WHEN 提交一条含多轨迹点的新记录
THEN 轨迹点按逐条插入写入
AND 可用于与优化路径对比

## MODIFIED Requirements

### Requirement: 瓶颈优化实录

**Previous**：压测变更记录轨迹逐条插入改为批量插入，并用开关保留基线；未规定运行时默认走哪条路径。

WHEN 记录压测优化案例,
系统 SHALL 保留优化前后的可复现对比（开关可回退），且日常默认配置 SHALL 走已验证的优化路径，而不是把基线路径当作默认。

#### Scenario: 优化可回退

GIVEN 已交付轨迹批量插入优化
WHEN 需要复现优化前基线
THEN 可通过关闭开关回到逐条插入
AND 不删除逐条实现

#### Scenario: 日常默认不是基线慢路径

GIVEN 使用仓库默认配置启动 record-service
WHEN 提交含轨迹点的记录
THEN 走批量插入优化路径

---

## 备注

- 本变更不产生新的 QPS/P95 数字；沿用 ADR-0002 已公布快照。
- 连接池默认大小、InnoDB 刷盘、GC 堆、MQ 消费参数不在本变更范围。
