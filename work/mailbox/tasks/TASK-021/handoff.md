# TASK-021 Handoff

## 【回传】短包

### 变更内容
- **spec.md**：补充父 pom.xml 静态检查插件配置要求
  - 添加 Checkstyle/Spotbugs/PMD 插件版本声明位置说明
  - 提供 pluginManagement 和 plugins 两处配置示例
  - 明确"若无配置则注释并说明需新增"的约束

### 待办事项
- [ ] 在父 pom.xml 的 `<properties>` 中添加三个插件版本号
- [ ] 在父 pom.xml 的 `<pluginManagement>` 中声明插件版本（可选）
- [ ] 在父 pom.xml 的 `<plugins>` 中启用静态检查（可选）

### 验收状态
- spec.md ✅ 已修改
- handoff.md ✅ 已回传
