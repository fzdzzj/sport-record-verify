# TASK-015 Handoff

## 【回传】短包

### 任务状态
- **任务**: TASK-015 资源管控 & 隔离
- **当前进度**: F1 高优先级 5 项并行中
- **变更内容**: Nacos 配置迁移方案补充

### 修改文件
1. `work/mailbox/tasks/TASK-015/spec.md` - 补充 Nacos 配置迁移方案

### Nacos 配置迁移要点
- ✅ 项目已集成 Nacos Client 依赖（spring-cloud-starter-alibaba-nacos-discovery/config）
- ✅ DataID: leaderboard-service.yml, Group: DEFAULT_GROUP
- ✅ HikariCP 连接池参数建议：minimum-idle=5, maximum-pool-size=20
- ✅ 线程池配置示例：corePoolSize=10, maxPoolSize=20, queueCapacity=100
- ✅ 配置优先级：Nacos > local (optional:nacos:)

### 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=ThreadPoolConfigTest
curl http://localhost:8080/actuator/metrics | grep threadpool
```

### 下一步行动
实现 ThreadPoolConfig.java 和 HikariCP 配置优化

---
**生成时间**: 2026-09-19
