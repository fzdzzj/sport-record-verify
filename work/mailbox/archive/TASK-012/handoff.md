【回传】TASK-012 安全增强短包

## 变更文件
1. spec.md - 已补充 SecurityFilterChain 配置与密码加密设计
2. gateway-service/pom.xml - **需先添加依赖**（当前无 spring-security）

## 核心设计
- **CSRF 防护**: 禁用 CSRF（Gateway 无会话场景）
- **密码加密**: BCryptPasswordEncoder 加密存储
- **路径规则**: /admin/**启用基础认证，/actuator/**限制访问

## 待办事项
- [ ] 在 gateway-service/pom.xml 中添加 `spring-boot-starter-security` 依赖
- [ ] 新建 SecurityConfig.java 实现 SecurityWebFilterChain
- [ ] 在 application.yml 中配置安全参数

## 验收标准
- CSRF 正确禁用
- BCrypt 加密/解密正常
- 路径权限控制生效

---
任务状态：spec 已完成，待依赖添加后实施代码修改。
