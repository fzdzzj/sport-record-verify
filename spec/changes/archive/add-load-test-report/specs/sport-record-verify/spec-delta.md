# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（压测与验收能力域，全部为新增）。

## ADDED Requirements

### Requirement: 并发压测方法
WHEN 系统进行性能验证,
系统 SHALL 对提交记录接口发起 100/500/1000 三档并发，并 SHALL 记录 P95、P99、QPS、错误率。

#### Scenario: 三档并发执行
GIVEN 压测脚本就绪，环境快照固定
WHEN 依次以 100、500、1000 并发压测提交接口
THEN 每档记录 P95/P99/QPS/错误率
AND 原始数据留存供优化前后对比

#### Scenario: 环境可复现
GIVEN 压测结论被引用
WHEN 复现压测
THEN 脚本与文档可重复执行
AND 记录环境快照（JDK/内存/中间件版本）保证可比

### Requirement: 量化达标
WHEN 系统以测试集（≥200 条，正负各半）验收,
系统 SHALL 达到拦截率 ≥90%、真实通过率 ≥95%、校验 P95 <200ms。

#### Scenario: 拦截率达标
GIVEN 伪造样本集
WHEN 运行校验引擎
THEN 拦截率 ≥90%

#### Scenario: 通过率达标
GIVEN 真实样本集
WHEN 运行校验引擎
THEN 通过率 ≥95%

#### Scenario: 延迟达标
GIVEN 校验链路运行
WHEN 统计响应时间
THEN P95 <200ms

#### Scenario: 未达标如实记录
GIVEN 任一指标未达标
WHEN 验收
THEN 如实记录实测值
AND 定位瓶颈并记录优化过程（不夸大）

### Requirement: 瓶颈优化实录
WHEN 压测暴露性能瓶颈,
系统 SHALL 定位并优化，且 SHALL 产出至少 1 个 Explain 慢查询案例与 1 个 GC/连接池调优案例，并 SHALL 记录优化前后对比。

#### Scenario: 慢查询案例
GIVEN 压测发现慢 SQL
WHEN 用 Explain 分析
THEN 加索引或改写 SQL
AND 记录优化前后 Explain 与耗时对比

#### Scenario: GC/连接池案例
GIVEN 压测发现 GC 停顿或连接池瓶颈
WHEN 调优
THEN 记录优化前后 GC 停顿或吞吐对比
AND 形成因果可解释的调优案例

### Requirement: 限流与熔断验证
WHEN 系统面对高并发或依赖故障,
系统 SHALL 由 Sentinel 在网关限流并对齐 5k QPS 目标，且 verify 不可用时 SHALL 熔断降级为「转人工」，主链路 SHALL 不挂。

#### Scenario: 限流拦截
GIVEN 请求超过限流阈值
WHEN 网关处理
THEN 超限请求被限流拦截
AND 返回限流提示

#### Scenario: 熔断降级转人工
GIVEN verify-service 不可用
WHEN record 侧调用校验
THEN 熔断降级为「转人工」状态
AND 提交主链路不挂（不因校验故障整体失败）

### Requirement: 压测沉淀
WHEN 压测与优化完成,
系统 SHALL 产出压测报告与 ADR，将方案、数据、优化因果 SHALL 写入 README/ADR 可复现文档。

#### Scenario: 报告产出
GIVEN 压测与优化已执行
WHEN 沉淀阶段
THEN 产出 docs/perf/压测报告.md（方案/环境/数据/图表/对比/结论）
AND 产出 ADR 记录优化因果
AND README 补摘要与复现命令

---

## 备注

- 本变更不新增业务功能，聚焦「真实数据 + 优化因果」沉淀，是 §12.3 A1 项（压测方案）的实现。
- 指标阈值（90%/95%/200ms）与压测并发档位（100/500/1000）以审批版 §8.2/§9 T12/§12.3 第 9 项与 A1 项为唯一依据。
- 已确认不购云服务器：压测在本地 Docker Compose 环境执行，结论按本地单机能力如实标注，不夸大（诚实口径）。