# TASK-018 后端研发工程

## 目标
静态检查（checkstyle/spotbugs/pmd）、代码坏味道治理。

## 只改文件
- leaderboard-service/pom.xml（添加 checkstyle/spotbugs/pmd 插件）
- leaderboard-service/src/main/resources/checkstyle.xml（新建）
- leaderboard-service/.editorconfig（新建）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp checkstyle:check spotbugs:check pmd:check
```

## 完成定义
- 静态检查通过
- 代码异味减少≥30%

---

# TASK-025 GatewayService Sentinel 限流

## 目标
为 GatewayService 增加 Sentinel 限流配置，支持 QPS 阈值动态调整。

## 只改文件
- work/mailbox/tasks/TASK-018/spec.md（本文件：补充 QPS 阈值说明）
- work/mailbox/tasks/TASK-025/handoff.md（回传短包）

## 当前配置
gateway-service 已集成 Sentinel 限流：
- 依赖：spring-cloud-starter-alibaba-sentinel + sentinel-gateway + sentinel-datasource-nacos
- 配置位置：gateway-service/src/main/resources/application.yml
- 规则数据源：Nacos (data-id: gateway-flow-rules, group: DEFAULT_GROUP)
- 默认 QPS 阈值：5000（Nacos 无规则时的回退值）
- 限流响应：HTTP 429 + JSON 提示

## QPS 阈值配置说明
application.yml 中 Sentinel 配置：
```yaml
sentinel:
  enabled: true
  eager: true
  scg:
    fallback:
      mode: response
      response-status: 429
      response-body: '{"code":429,"message":"请求过于频繁，请稍后重试（网关限流）"}'
```

Nacos 规则 data-id: `gateway-flow-rules`
- 默认回退 QPS: 5000
- 规则格式：GatewayFlowRule (resource/count/intervalSec/grade)
- 动态更新：修改 Nacos 配置不重启生效

## 验收检查
1. gateway-service/pom.xml 包含 sentinel 依赖 ✓
2. application.yml 中 sentinel 配置完整 ✓
3. Nacos 规则 data-id 正确：gateway-flow-rules ✓
4. 限流降级响应：429 + JSON 提示 ✓

## 完成定义
- Sentinel 依赖存在且版本兼容
- QPS 阈值配置可动态调整
- 限流降级行为符合预期
