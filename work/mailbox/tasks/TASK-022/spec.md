# TASK-022 GatewayService 灰度发布能力

## 目标
为 GatewayService 增加灰度发布能力，支持基于请求属性的流量分流策略。

## 只改文件
- work/mailbox/tasks/TASK-022/spec.md（本文件：补充网关路由策略）
- work/mailbox/tasks/TASK-022/handoff.md（回传短包）

## 网关路由策略
### 灰度版本标识
- 请求头 `X-Gray-Version`：指定目标灰度版本（如 v1, v2, canary）
- 请求头 `X-User-Id`：按用户 ID 哈希固定路由到特定版本（用于白名单测试）
- 权重分配：支持按比例分流（如 90% 到 stable，10% 到 canary）

### 路由配置示例
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: route-leaderboard-service-canary
          uri: lb://leaderboard-service-canary
          predicates:
            - Path=/leaderboard/**
          filters:
            - StripPrefix=1
            - Name=GrayRouteFilter
              args:
                version: canary
                matchHeader: X-Gray-Version
                weight: 10
```

### 需要修改的部分（需先设计灰度规则）
<!-- 
TODO: 以下配置需等待灰度规则设计完成后实施：
- GrayRouteFilter 过滤器实现（基于请求属性匹配目标版本服务）
- Nacos 配置中心动态更新灰度规则
- 灰度版本服务发现与负载均衡策略
- 灰度流量监控与回滚机制
-->

## 验收命令
```bash
# 启动网关服务
cd gateway-service && mvn spring-boot:run

# 测试灰度路由（发送带版本头的请求）
curl -H "X-Gray-Version: canary" http://localhost:8080/leaderboard/api/leaderboard/top
curl -H "X-User-Id: 12345" http://localhost:8080/leaderboard/api/leaderboard/top
```

## 完成定义
- 网关能根据请求头正确路由到灰度版本服务
- 权重分流策略生效
- 灰度流量可监控
