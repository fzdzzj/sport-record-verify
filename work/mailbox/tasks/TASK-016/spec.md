# TASK-016 第三方客户端 SDK 治理

## 目标
外部接口客户端封装、超时/重试策略、故障隔离。统一 LocalDateTime 序列化格式为 ISO-8601（yyyy-MM-dd HH:mm:ss）。

## 只改文件
- common/src/main/java/com/sportverify/common/config/JacksonConfig.java（新建）
  - 配置 ObjectMapper 自定义序列化器：LocalDateTime → "yyyy-MM-dd HH:mm:ss"
  - 需要新建配置类（@Configuration + @Bean）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/client/ExternalClient.java（新建）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/FeignHttpClientPoolConfig.java（新建）
- leaderboard-service/pom.xml（添加 io.github.resilience4j:resilience4j-retry）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=ExternalClientTest
curl -X GET http://localhost:8080/api/v1/external/call
```

## 完成定义
- 超时控制生效
- 重试策略正常
- 故障隔离正常
