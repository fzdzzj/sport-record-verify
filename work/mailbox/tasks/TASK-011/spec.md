# TASK-011 MapMatchService 熔断增强

## 目标
为 MapMatchService 调用第三方 API（路网匹配服务）增加熔断机制，防止级联故障。

## 只改文件
- mapmatch-service/pom.xml（添加 resilience4j 依赖）**需先添加依赖**
- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java
- mapmatch-service/src/main/resources/application.yml（新建或追加配置）

## fallback 方法设计
### 策略选择
- **降级方式**: 返回安全默认值（matchedRatio=0, offRoadRatio=1, avgDistance=0, maxDistance=0）
- **触发条件**: CircuitBreaker 处于 OPEN 状态或调用超时/异常
- **日志记录**: 降级时记录 WARN 级别日志，包含原始异常信息

### 代码实现
```java
@CircuitBreaker(name = "mapMatchApi", fallbackMethod = "mapMatchFallback")
public MapMatchResultDTO match(MapMatchRequestDTO request) { ... }

private MapMatchResultDTO mapMatchFallback(MapMatchRequestDTO request, Throwable t) {
    log.warn("MapMatch 熔断降级：{}", t.getMessage());
    // 返回全零指标（除 offRoadRatio=1 表示离路），不抛异常
    return new MapMatchResultDTO(0, 1, 0, 0, 0, request != null ? request.getPoints().size() : 0);
}
```

### Resilience4j 配置（application.yml）
```yaml
resilience4j:
  circuitbreaker:
    instances:
      mapMatchApi:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpen: true
```

## 验收命令
```bash
mvn -B -ntp test -Dtest=MapMatchServiceTest
```

## 完成定义
- CircuitBreaker 正常开关状态切换
- 降级方法返回安全默认值
- 熔断日志可观测（Actuator metrics + logs）

## 说明
**需先添加依赖**: mapmatch-service/pom.xml 当前无 resilience4j 依赖，注释掉"需要修改"部分并说明"需先添加依赖"。
后续需在 pom.xml 中添加 `spring-cloud-starter-circuitbreaker-resilience4j` 依赖后方可完整实现。
