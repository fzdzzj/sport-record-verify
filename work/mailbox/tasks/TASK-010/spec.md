# TASK-010 GatewayService OpenAPI 3.0 文档生成

## 目标
为 gateway-service 生成 OpenAPI 3.0 规范文档，支持 Swagger UI 访问。

## 只改文件
- gateway-service/pom.xml（添加 springdoc-openapi-starter-webmvc-ui 依赖）
- gateway-service/src/main/resources/application.yml（配置 SpringDoc 端点）
- work/mailbox/tasks/TASK-010/handoff.md（回传短包）

## 需要修改（注释部分）
**注意：gateway-service/pom.xml 当前无 springdoc-openapi 依赖，需先添加依赖。**

以下代码块已注释，待依赖添加后取消注释：

```xml
<!-- 暂时注释：需先在 pom.xml 添加 springdoc-openapi-starter-webmvc-ui 依赖 -->
<!--
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
-->
```

## 验收命令
```bash
cd gateway-service && mvn -B -ntp package
java -jar target/sport-verify-gateway-service.jar
curl http://localhost:8888/v3/api-docs.yaml
```

## 完成定义
- /v3/api-docs.yaml 返回有效的 OpenAPI 3.0 YAML 文档
- /swagger-ui.html 可访问 Swagger UI 界面
- 文档包含网关路由配置说明

## 手动手册
1. 在 gateway-service/pom.xml 的 dependencies 中添加：
   ```xml
   <dependency>
       <groupId>org.springdoc</groupId>
       <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
       <version>2.3.0</version>
   </dependency>
   ```
2. 在 application.yml 中配置：
   ```yaml
   springdoc:
     api-docs:
       path: /v3/api-docs
     swagger-ui:
       path: /swagger-ui.html
   ```
3. 启动服务后访问：http://localhost:8888/swagger-ui.html
