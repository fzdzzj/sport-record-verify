# TASK-014 GatewayService 增加 Sleuth 链路追踪

## 目标
为 GatewayService 添加 Spring Cloud Sleuth 链路追踪，集成 Zipkin 作为后端存储。

## 只改文件
- gateway-service/pom.xml（添加 Sleuth 依赖）
- gateway-service/src/main/resources/application.yml（配置 Zipkin 端点）

## 验收命令
```bash
cd gateway-service && mvn -B -ntp clean compile
```

## 完成定义
- [ ] pom.xml 包含 sleuth-starter 和 sleuth-bridge-otel 依赖
- [ ] application.yml 配置 spring.sleuth.sampler.percentage=100
- [ ] application.yml 配置 spring.sleuth.zipkin.baseUrl=http://zipkin:9411/api/v2/spans
- [ ] 编译通过无错误

## 需要修改的部分

### pom.xml 依赖（需先添加 Sleuth 依赖）
**注意：当前父 pom 未声明 Sleuth 版本管理，需在 dependencyManagement 中先添加。**

建议添加以下依赖（需等待依赖添加后才能使用）：
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.7.0</version>
</dependency>
```

### application.yml 配置
```yaml
spring:
  sleuth:
    sampler:
      percentage: 100.0
    zipkin:
      enabled: true
      baseUrl: http://zipkin:9411/api/v2/spans
```

**说明：由于当前项目 pom.xml 中缺少 Sleuth 相关依赖，上述配置暂时无法生效。需要先添加 Sleuth 依赖到父 pom 的 dependencyManagement 中。**
