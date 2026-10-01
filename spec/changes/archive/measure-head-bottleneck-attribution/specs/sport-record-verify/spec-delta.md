# spec-delta：measure-head-bottleneck-attribution

本文件包含对 `spec/specs/sport-record-verify/spec.md`「压测」域的新增需求。不修改已落地的并发档位、量化达标阈值、批量插入默认路径。

## ADDED Requirements

### Requirement: 优化前先做同一负载归因

WHEN 准备优化已有性能路径,
系统 SHALL 先使用优化前后可重复的同一负载记录延迟、吞吐和资源占用,
并 SHALL 把主要耗时归入业务规则、数据库、远程调用、锁、CPU 或 GC 之一。

#### Scenario: 没有归因不得开始优化

GIVEN 只有一次延迟或吞吐结果，或只有旧环境压测报告
WHEN 不能说明当前 HEAD 上主要耗时属于哪一类因素
THEN 不得开始代码或参数优化
AND 先补同一负载下的归因证据

#### Scenario: 旧报告不得冒充当前 HEAD

GIVEN docs/perf 中存在更早环境的 QPS 或 P95
WHEN 编写当前 HEAD 的归因结论
THEN 必须标明那些数字来自旧报告
AND 不得把它们写成当前 HEAD 的实测值

#### Scenario: 只选择占比最高的一类

GIVEN 同一负载已经给出各类耗时占比或调用次数
WHEN 选择本次优化对象
THEN 只选择占比最高的一类
AND 不在同一次变更中混合修改其他类别

### Requirement: 一次只改一类因素并用同一基线验收

WHEN 实施一次性能优化,
系统 SHALL 只修改本次归因选中的一类因素,
并 SHALL 用优化前同一负载、样本和并发档复测。

#### Scenario: 指标没有改善就回到度量

GIVEN 已完成一次单因素改动
WHEN 同一基线复测显示目标指标没有改善
THEN 停止继续叠加参数或无关改动
AND 重新度量瓶颈

#### Scenario: 调用次数未降不得先调 JVM

GIVEN 主要耗时来自 SQL、HTTP 或事务范围
WHEN 这些调用次数或事务范围尚未下降
THEN 不得把 JVM、堆或 GC 参数调整作为本次优化
AND 先减少调用或缩短事务

#### Scenario: 本轮度量不实施优化

GIVEN 当前变更的目标是产出 HEAD 归因表
WHEN 已经选出占比最高的一类因素
THEN 只记录该类因素与下一步假设
AND 不在本变更中修改业务代码、索引、连接池或 JVM 参数

### Requirement: 性能优化不得改变关键语义

WHEN 为性能修改业务路径,
系统 SHALL 保持金额、库存、权限、幂等和治理凭证的既有语义。

#### Scenario: 拒绝以变快为理由改变准入

GIVEN 一次优化可以减少远程调用或事务时间
WHEN 该改动会改变权限判定、幂等结果或治理凭证校验
THEN 不采用该改动
AND 另找不改变这些语义的方案