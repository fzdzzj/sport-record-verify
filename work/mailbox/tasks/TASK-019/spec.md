# TASK-019 流量治理

## 目标
网关层流量染色、镜像、优先级。

## 只改文件
- gateway-service/src/main/java/com/sportverify/gateway/config/FlowControlConfig.java（新建）
- gateway-service/src/main/resources/application.yml（添加 Sentinel 配置）
- gateway-service/pom.xml（添加 alibaba-sentinel）

## BOM 管理方案说明
父 pom.xml 已声明 spring-cloud-alibaba-dependencies BOM（2023.0.1.0），其中包含：
- spring-cloud-starter-alibaba-sentinel
- spring-cloud-alibaba-sentinel-gateway

但 sentinel-datasource-nacos 和 sentinel-datasource-extension 不在 SCA BOM 中，属于阿里独立构件，需显式锁定版本 1.8.6 与 SCA 内置的 sentinel-core 保持一致。

gateway-service/pom.xml 已在 dependencyManagement 之外直接声明了这两个依赖的版本（1.8.6），符合规范。

若未来需要统一管理第三方依赖版本，可考虑新建 BOM 模块 sport-verify-bom，将非 BOM 管理的依赖集中管理。当前无需修改。

## 验收命令
```bash
cd gateway-service && mvn -B -ntp test -Dtest=FlowControlConfigTest
curl -H "X-Trace-Id: 123" http://localhost:8080/api/v1/leaderboard/top
```

## 完成定义
- 流量染色正常
- 优先级控制生效
