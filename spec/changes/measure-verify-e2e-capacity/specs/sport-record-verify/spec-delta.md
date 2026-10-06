# 变更增量规格：verify-service 端到端全链路容量与压测重评

## 1. 变更背景与架构依据

- **多项优化综合评定诉求**：TASK-163（调度间隔）、TASK-169（分块标记）、TASK-171（批内并发）、TASK-173（聚合预取契约）落地后，尚未在同一轮次下进行端到端全链路复测；
- **既有测量局部性**：TASK-143 为四服务局部覆盖且 R5 全降级、榜单缺席；TASK-138 属于旧 HEAD/旧参数基准；
- **纯测量类规范**：本增量不修改系统运行时业务逻辑，仅定义端到端容量重评的测量口径、覆盖面要求、降级分支登记义务与历史数据比对纪律。

---

## 2. 需求增量（Delta）

### ADDED Requirement: 端到端全链路容量重评指标与分段口径

WHEN 对当前系统版本执行端到端容量重评压测,
系统/执行者 SHALL 采集提交端指标（QPS、墙钟耗时、P50/P95/P99 延迟、成功率）、事件三分段延迟、判定完成速率与 outbox 排空斜率，
且 SHALL 仅对同一运行可成对关联的样本计算差值。

#### Scenario: 全链路正常运行测量
GIVEN gateway、user、record、verify、leaderboard、mapmatch 六服务及中间件均健康就绪，且 R5 空间匹配未发生降级
WHEN 施加端到端压测负载
THEN 完整收集各阶段成对指标，分别列出样本数、时间精度与百分位延迟（P50/P95/P99）
AND 各阶段独立 P50 保持独立披露，不得简单求和计算端到端总耗时

#### Scenario: 关联样本与精度声明
GIVEN 压测请求携带可追踪标识（runId/requestId/recordId/eventId）
WHEN 收集跨进程（如 pub→consume）与同进程（如 consume→callback）日志
THEN 跨进程阶段明确标记时间戳时钟源与粗粒度精度
AND 同进程阶段采用毫秒级精度细分，失配或重复样本独立记账

### ADDED Requirement: 环境覆盖面与降级分支登记义务

WHEN 执行端到端容量压测前,
系统/执行者 SHALL 检查服务拓扑与中间件健康状态；若具备全链路条件则按全链路测量；
若任一服务或中间件不可用（如 mapmatch/PostGIS 或 leaderboard 无法就绪），系统/执行者 SHALL 如实降级并登记为「局部覆盖（列明缺席项）」，
SHALL NOT 伪称全链路，SHALL NOT 为凑齐覆盖修改生产配置；
若基础服务与中间件均无法安全就绪，SHALL 登记「测量未执行」证据不足分支，SHALL NOT 伪造读数。

#### Scenario: 完整全链路就绪
GIVEN 全链路六服务与真实路网、全中间件健康运行
WHEN 执行压测
THEN 报告全链路覆盖，标明真实 R5 与榜单链路全流程参与

#### Scenario: 局部覆盖降级登记
GIVEN leaderboard 或 mapmatch/PostGIS 不可用
WHEN 执行压测
THEN 在报告中明确登记为局部覆盖，逐项列明缺席组件与降级原因，限定结论适用范围

#### Scenario: 环境不可用直接收口
GIVEN 基础四服务或核心中间件无法就绪
WHEN 评估压测前置条件
THEN 终止执行，登记「测量未执行」且不报告吞吐与延迟数字

### ADDED Requirement: 测量结果历史比对纪律与外推限制

WHEN 出具端到端容量重评报告时,
系统/执行者 SHALL 将 TASK-138 与 TASK-143 历史数字仅列为历史参考，
SHALL NOT 做出翻案裁决，SHALL NOT 计算改善百分比，
且性能结论 SHALL 显式限定为「本机环境、本负载模型、未达外部门槛」，SHALL NOT 为生产实际容量背书。

#### Scenario: 历史参考数字列出与不翻案声明
GIVEN 存在 TASK-138（旧 HEAD）与 TASK-143（四服务局部覆盖）历史读数
WHEN 编撰当前重评报告
THEN 历史读数仅并列记录作为演进参考，明确说明参数与环境差异，不做翻案裁决

#### Scenario: 性能结论范围严格限定
GIVEN 获得当前轮次的吞吐与延迟数据
WHEN 报告总结与交付
THEN 明确声明结论限定于本机硬件与指定压测参数，不外推为通用生产容量保障
