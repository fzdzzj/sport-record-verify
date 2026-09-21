# TASK-015 资源管控 & 隔离

## 目标
线程池隔离、连接池调优、CPU/内存限制。

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/ThreadPoolConfig.java（新建）
- leaderboard-service/src/main/resources/application.yml（配置 HikariCP + Nacos 配置迁移）
- leaderboard-service/pom.xml（添加 spring-boot-starter-actuator）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=ThreadPoolConfigTest
curl http://localhost:8080/actuator/metrics | grep threadpool
```

## 完成定义
- 线程池隔离正常
- 连接池参数优化

## Nacos 配置迁移方案（需要修改）
**说明**: 本项目已集成 Nacos Client 依赖（见 leaderboard-service/pom.xml），需将本地 application.yml 中的配置迁移至 Nacos 配置中心。

### 1. Nacos 配置结构
```
Nacos Server: 127.0.0.1:8848
DataID: leaderboard-service.yml (或 leaderboard-service.yaml)
Group: DEFAULT_GROUP
命名空间：public
```

### 2. 配置迁移步骤
1. **保留必要配置到本地**（启动必需，Nacos 未就绪时服务可启动）:
   - `spring.config.import: optional:nacos:leaderboard-service.yml`（确保可选导入）
   
2. **迁移至 Nacos 的配置项**:
   - `server.port`: 服务端口
   - `spring.application.name`: 应用名称
   - `spring.cloud.nacos.*`: Nacos 连接配置
   - `spring.datasource.*`: 数据源配置（URL、username、password）
   - `spring.data.redis.*`: Redis 配置
   - `rocketmq.*`: MQ 配置
   - `mybatis-plus.*`: MyBatis 配置
   - `app.auth.*`, `app.internal.*`: 认证配置
   - `resilience4j.*`: 熔断器配置
   - `management.*`: Actuator 配置
   - `logging.*`: 日志配置
   - `xxl.job.*`: XXL-JOB 配置

3. **HikariCP 连接池配置迁移**:
   ```yaml
   spring:
     datasource:
       hikari:
         minimum-idle: 5                    # 最小空闲连接
         maximum-pool-size: 20              # 最大连接数
         connection-timeout: 30000          # 连接超时 (ms)
         idle-timeout: 600000               # 空闲连接超时 (ms)
         max-lifetime: 1800000              # 连接最大生命周期 (ms)
         connection-test-query: SELECT 1    # 连接测试查询
   ```

### 3. 线程池配置建议（ThreadPoolConfig.java）
```java
@Configuration
@EnableConfigurationProperties(ThreadPoolProperties.class)
public class ThreadPoolConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("task-executor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

### 4. 配置优先级
```
Nacos 配置 > application.yml（本地兜底）
```
- Spring Boot 启动时优先从 Nacos 拉取远程配置
- 本地 application.yml 作为兜底配置，防止 Nacos 不可用时服务无法启动
- 使用 `optional:` 前缀确保 Nacos 未启动时服务仍可启动

### 5. 注意事项
- **不准猜测**: 若项目无 Nacos 依赖，需先添加 Nacos Client 依赖
  - `spring-cloud-starter-alibaba-nacos-discovery`
  - `spring-cloud-starter-alibaba-nacos-config`
- 实际项目中需确保 Nacos Server 运行在指定地址
- 敏感配置（如数据库密码）建议使用 Nacos 加密功能
