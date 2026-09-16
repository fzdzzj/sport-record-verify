# 提案：启动脚本加上 G1 停顿目标（不固定堆）

## Why

ADR-0002 已否决 `-Xms1g -Xmx1g`：固定大堆后单次停顿变差，1000 并发出现 55~147ms G1 Evacuation。后续项写的是「G1 自适应 + MaxGCPauseMillis=50」。

当前启动脚本（`logs/start-trace-e2e.ps1` 等）只是 `java -jar`，没有停顿目标。本变更只加**不停堆大小、只设停顿目标**的可选 JAVA_OPTS，避免把已否决的固定堆又带回来。

**背景**：
- 批量插入已降低 Young GC 次数（565→297）。
- 没有新的 GC 日志，本变更不声称新停顿数字。

**当前状态**：无 MaxGCPauseMillis。

**期望状态**：启动说明/脚本提供 `JAVA_OPTS` 含 `-XX:MaxGCPauseMillis=50`，明确禁止同时加固定 1g 堆；默认脚本不写 -Xms/-Xmx。

## What Changes

- 启动脚本或 README 启动段：可选 JAVA_OPTS=`-XX:MaxGCPauseMillis=50`。
- ADR-0002 GC 节补一句：推荐停顿目标，固定堆仍不采纳。
- 不改业务代码，不重跑 15 分钟 GC 压测。

**明确不做**：不设 -Xms1g/-Xmx1g；不改 collector 为 ZGC；不把 GC 参数写进应用 yml。

## Impact

### 受影响的规范
- ADDED「JVM 停顿目标可选」。

### 受影响的代码
- 启动脚本 / README；ADR-0002

### 用户影响
- 不改 API。按文档启动才带停顿目标。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新

## 时间线评估

小：约 0.3 天。

## 风险

- **停顿目标过小导致吞吐掉下来** → 缓解：只作为可选 JAVA_OPTS，默认脚本可加注释而不强制。
- **连同固定堆一起加回去** → 缓解：提案写死禁止。
