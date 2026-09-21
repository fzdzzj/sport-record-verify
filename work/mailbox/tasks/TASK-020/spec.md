# TASK-020 数据治理

## 目标
历史数据归档、冷热数据分离、大字段处理。

## 兼容性说明
本工程已锁定 Java 21（父 pom.xml:34-36,182-192），maven-compiler-plugin 配置 release=21，maven-enforcer-plugin 强制校验 JDK≥21。若需向下兼容 Java 17+，需在父 pom.xml 的 maven-compiler-plugin 中增加 source/target 配置（当前 release=21 仅向上兼容）。

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/ArchiveService.java（新建）
- leaderboard-service/src/main/resources/db/migration/V10__archive_old_data.sql（新建）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=ArchiveServiceTest
mysql> SELECT COUNT(*) FROM leaderboard_contribution_archive;
```

## 完成定义
- 历史数据归档成功
- 冷热数据分离正常
