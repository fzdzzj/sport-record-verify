【回传】TASK-011 熔断设计短包

## 变更文件
1. spec.md - 已补充 fallback 方法设计与 Resilience4j 配置
2. mapmatch-service/pom.xml - **需先添加依赖**（当前无 resilience4j）

## 核心设计
- **降级策略**: 返回安全默认值（matchedRatio=0, offRoadRatio=1, avgDistance=0, maxDistance=0）
- **触发条件**: CircuitBreaker OPEN 状态 / 调用超时 / 异常
- **实现方式**: @CircuitBreaker + fallbackMethod 模式

## 待办事项
- [ ] 在 mapmatch-service/pom.xml 中添加 `spring-cloud-starter-circuitbreaker-resilience4j` 依赖
- [ ] 在 MapMatchService.java 中实现 @CircuitBreaker 注解与 fallback 方法
- [ ] 在 application.yml 中配置 circuitbreaker 参数

## 验收标准
- CircuitBreaker 状态切换正常
- 降级方法返回安全默认值不抛异常
- Actuator metrics 可观测熔断指标

---
任务状态：spec 已完成，待依赖添加后实施代码修改。
