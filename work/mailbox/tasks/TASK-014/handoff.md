# TASK-014 Handoff

## 回传

### Spec 修改摘要
- **文件**: `work/mailbox/tasks/TASK-014/spec.md`
- **内容**: 
  - 更新任务目标为"GatewayService 增加 Sleuth 链路追踪"
  - 明确只改文件：pom.xml + application.yml
  - 补充 Zipkin 配置说明（sampler.percentage=100, baseUrl）
  - 标注当前依赖缺失状态，需先添加 Sleuth 依赖到父 pom

### 关键发现
**gateway-service/pom.xml 中无 Sleuth 相关依赖**，父 pom.xml 的 dependencyManagement 也未声明 Sleuth 版本。

建议先添加以下依赖管理条目：
```xml
<spring-cloud-sleuth.version>3.1.9</spring-cloud-sleuth.version>
<!-- 或对齐 Cloud 2023.0.1 的 sleuth 版本 -->
```

### 待办事项
1. 在父 pom.xml 添加 Sleuth BOM 或版本管理
2. 在 gateway-service/pom.xml 添加 spring-cloud-starter-sleuth 依赖
3. 在 gateway-service/application.yml 添加 Zipkin 配置
4. 验证编译通过

---

【回传】短包已生成
