# TASK-013 序列化 & 网络传输

## 目标
引入 Protobuf 优化序列化性能，启用 HTTP/2。

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/ProtobufConfig.java（新建）
- leaderboard-service/src/main/resources/application.yml（启用 HTTP/2）
- leaderboard-service/pom.xml（添加 com.google.protobuf:protobuf-java）

## Micrometer Actuator 指标暴露（F1 高优先级并行任务）

### 当前状态
✅ **已完成**：所有 6 个服务已配置 Actuator/Micrometer 指标暴露

| 服务 | 端口 | 配置文件 | Actuator 配置位置 |
|------|------|----------|------------------|
| record-service | 8082 | application.properties | line 84-88 |
| user-service | 8081 | application.yml | line 77-95 |
| verify-service | 8083 | application.yml | line 156-174 |
| leaderboard-service | 8084 | application.yml | line 100-118 |
| mapmatch-service | 8085 | application.yml | line 55-72 |
| gateway-service | 8080 | application.yml | line 131-149 |

### 关键配置项
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    distribution:
      percentiles-histogram:
        http:
          server:
            requests: true  # P95 计算必需
  endpoint:
    health:
      show-details: always
```

### 验收命令
```bash
# Prometheus 端点验证
curl http://localhost:8082/actuator/prometheus
curl http://localhost:8081/actuator/prometheus
curl http://localhost:8083/actuator/prometheus
curl http://localhost:8084/actuator/prometheus
curl http://localhost:8085/actuator/prometheus
curl http://localhost:8080/actuator/prometheus
```

**注意**：需先配置 Prometheus/Grafana 才能实际使用这些指标进行监控和告警。

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=ProtobufSerializerTest
curl -H "Content-Type: application/x-protobuf" http://localhost:8080/api/v1/leaderboard/top
```

## 完成定义
- 序列化性能提升≥50%
- HTTP/2 正常启用
