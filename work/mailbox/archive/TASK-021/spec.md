# TASK-021 缓存体系增强

## 目标
穿透/击穿/雪崩防护、缓存内存管控、TTL 优化。

## 只改文件
- **pom.xml**（父工程）：新增 Checkstyle/Spotbugs/PMD 静态检查插件配置
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java（增强现有配置）
- leaderboard-service/src/main/resources/application.yml（调整缓存参数）

## 父 pom 插件配置要求
在 `<pluginManagement>` 中添加以下插件版本声明：
```xml
<maven-checkstyle-plugin.version>3.3.1</maven-checkstyle-plugin.version>
<maven-pmd-plugin.version>3.21.2</maven-pmd-plugin.version>
<spotbugs-maven-plugin.version>4.8.6.0</spotbugs-maven-plugin.version>
```

在 `<plugins>` 中启用静态检查（可选，默认不强制）：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <version>${maven-checkstyle-plugin.version}</version>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-pmd-plugin</artifactId>
    <version>${maven-pmd-plugin.version}</version>
</plugin>
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <version>${spotbugs-maven-plugin.version}</version>
</plugin>
```

**注意**：若父 pom.xml 已有相关配置，则按需补充；若无，则注释掉"需要修改"部分并说明"需新增配置"。

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=CacheConfigTest
curl -X GET http://localhost:8080/api/v1/leaderboard/top?limit=10
# 验证缓存穿透防护
```

## 完成定义
- 穿透/击穿/雪崩防护生效
- 缓存内存正常管控
- 父 pom 已添加 Checkstyle/Spotbugs/PMD 插件版本声明
