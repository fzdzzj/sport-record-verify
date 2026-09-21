【回传】短包：

## Micrometer Actuator 配置状态

所有 6 个服务已启用 Actuator/Micrometer 指标暴露：

| 服务 | 端口 | 配置文件 | Actuator 状态 |
|------|------|----------|--------------|
| record-service | 8082 | application.properties | ✅ 已配置 (line 84-88) |
| user-service | 8081 | application.yml | ✅ 已配置 (line 77-95) |
| verify-service | 8083 | application.yml | ✅ 已配置 (line 156-174) |
| leaderboard-service | 8084 | application.yml | ✅ 已配置 (line 100-118) |
| mapmatch-service | 8085 | application.yml | ✅ 已配置 (line 55-72) |
| gateway-service | 8080 | application.yml | ✅ 已配置 (line 131-149) |

### 关键配置项
- `management.endpoints.web.exposure.include`: health,info,prometheus,metrics
- `management.metrics.distribution.percentiles-histogram.http.server.requests`: true (P95 计算必需)
- `management.endpoint.health.show-details`: always

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
