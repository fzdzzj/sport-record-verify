# TASK-023 Handoff

## 回传短包

**任务**: TASK-023 部署运维（含敏感数据脱敏）

**修改内容**:
1. ✅ spec.md - 补充脱敏注解设计 (@Masked + MaskType + SensitiveUtils)
2. ⚠️ common 模块无现成工具类，需新建：
   - `common/src/main/java/com/sportverify/common/annotation/Masked.java`
   - `common/src/main/java/com/sportverify/common/enums/MaskType.java`
   - `common/src/main/java/com/sportverify/common/util/SensitiveUtils.java`
3. ⏸️ leaderboard-service 实体类待添加 @Masked 注解标记敏感字段
4. ⏸️ leaderboard-service/k8s/deployment.yaml 待创建
5. ⏸️ leaderboard-service/Dockerfile 已存在，可复用

**验收命令**:
```bash
cd leaderboard-service && mvn -B -ntp package
docker build -t leaderboard-service:latest .
kubectl apply -f k8s/deployment.yaml
```

**状态**: F1 高优先级并行中，等待后续实现脱敏工具类与 K8s 编排。
