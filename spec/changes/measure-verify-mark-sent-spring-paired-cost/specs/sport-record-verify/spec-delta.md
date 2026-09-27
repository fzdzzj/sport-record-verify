## ADDED Requirements

### Requirement: markSent 外层与下层的同调用逐次配对必须先经隔离真库判别
WHEN 需要用 TASK-146 的外层混合墙钟拆出「下层 `StatementHandler.update` 之外的残余」,
系统 SHALL 先在隔离真 MySQL 和 Spring 注入的真实 Mapper 路径中，对**同一次** `markSent` 调用逐次同时取得外层 Mapper 调用墙钟与内层整个 `StatementHandler.update` 调用墙钟，SHALL 只对成功配对的**同一调用**计算「外层减内层」残余；SHALL NOT 用两组独立分位数相减代替逐次配对；SHALL NOT 将残余命名为连接获取、prepare/绑定、提交或服务端 SQL 中的任何单项。

#### Scenario: 一次外层调用对应一条内层样本
GIVEN 专用 scratch schema 有 PENDING outbox 行，调用者无业务事务且测试探针启用
WHEN 同线程对同一个 `markSent` 调用同时记录外层与内层读数
THEN 该外层调用 SHALL 恰配一条目标 statement 的低基数内层样本（无缺配、无多配）
AND 更新行数、成功/失败 SHALL 在外层返回值与内层样本间一致
AND 样本 SHALL NOT 含 eventId、payload、凭证或 SQL 参数

#### Scenario: 有界小样本与可重复性
GIVEN 预热与至少三轮测量
WHEN 汇总逐轮残余
THEN 报告 SHALL 给出每轮样本数、缺配/多配计数、残余的逐次聚合（非两组独立 P50 相减）与轮间波动
AND SHALL 记录探针关闭的无插件对照以判断探针是否改变外层墙钟
AND 探针扰动不可接受或轮间不可重复时 SHALL 停止归因并保留原始反例，不换第二种探针

### Requirement: 配对成本结论等级与运行纪律
WHEN 汇报 TASK-149 的 Spring 切片配对成本,
系统 SHALL 将结论限定为该受限切片中「小样本同调用分账可重复且语义不变」；SHALL NOT 声称纯服务端 SQL、fsync、池等待、生产 P99 或吞吐收益；SHALL NOT 据此授权 SQL 批量化或改动生产插件链、Mapper SQL、索引、relay 默认值、MQ、连接池与 JVM。

#### Scenario: 语义不变与不可测段
GIVEN 探针开启与无插件基线对照
WHEN 复核目标调用的最终状态、`sent_at`、异常类型、独立连接可见性与连接归还
THEN 二者 SHALL 与无插件基线一致，失败后作用域 SHALL 清理且后续调用 SHALL 不串样本
AND 连接获取、prepare/绑定、显式提交、服务端 SQL 与网络往返的拆分若未直接观测 SHALL 明记未知

#### Scenario: 干净检出复核先于新测量
GIVEN 工作树存在归属不明的未跟踪测试文件
WHEN 开展新的成本测量前
THEN 系统 SHALL 先在仅含已提交基线的隔离检出上重跑唯一入口 offline 测试与 TASK-148 条件式真库 IT，确认其不依赖该未跟踪文件
AND 不能复现时 SHALL 判为证据缺口并停止新测量，不把编译红、环境红或跳过当作行为红