# TASK-010 回传短包

## 任务状态
- 任务名称：GatewayService OpenAPI 3.0 文档生成
- 当前进度：spec.md 已更新，等待依赖添加后实施

## 回传内容
### spec.md 变更
- 任务目标从"可观测性"改为"OpenAPI 3.0 文档生成"
- 修改文件列表：gateway-service/pom.xml、application.yml、handoff.md
- 添加了注释说明：需先添加 springdoc-openapi-starter-webmvc-ui 依赖
- 包含验收命令和完成定义
- 提供手动手册供后续实施参考

### 待办事项
1. 在 gateway-service/pom.xml 添加 springdoc-openapi-starter-webmvc-ui 依赖（版本 2.3.0）
2. 在 application.yml 配置 SpringDoc 端点路径
3. 启动服务验证 /v3/api-docs.yaml 和 /swagger-ui.html

## 注意事项
**不准猜测：若 gateway-service/pom.xml 无 springdoc-openapi 依赖，需先添加依赖。**

---
【回传】TASK-010 spec 已更新，待依赖添加后继续实施。
