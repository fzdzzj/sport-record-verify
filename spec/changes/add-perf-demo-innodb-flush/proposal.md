# 提案：演示环境可选放宽 InnoDB 刷盘（非生产）

## Why

压测报告 §10 后续项 2 把 `innodb_flush_log_at_trx_commit=2` 列为吞吐天花板选项之一。这是用「每秒刷盘」换提交 QPS，牺牲的是崩溃时最多约 1 秒 redo。

本项目是本地演示，不是生产多副本。若把它写进默认 `docker-compose.yml`，读者会以为这是生产口径。所以：**只允许可选覆盖文件，默认 compose 保持 1。**

**背景**：
- 提交主瓶颈已由批量 INSERT 解释（另案改默认）。刷盘不是第一因果。
- 报告写明结论不外推生产。

**当前状态**：MySQL 容器未改刷盘，默认即 1（每次提交刷 redo）。

**期望状态**：新增可选 `docker-compose.perf.yml`（或等价 override），仅演示压测时启用 `innodb_flush_log_at_trx_commit=2`；README/ADR 写清耐久性取舍；默认 `docker compose up` 不启用。

## What Changes

- 新增可选 compose overlay，给 MySQL 加 `--innodb-flush-log-at-trx-commit=2`。
- README / ADR-0002 / 压测报告：默认路径不改刷盘；要复现「演示吞吐」才 overlay。
- 不改业务代码，不改默认 compose 命令。

**明确不做**：生产 profile 不采用 2；不把该参数当架构亮点写进主规范能力清单以外的吹嘘句；不与批量插入默认混在一个变更。

## Impact

### 受影响的规范
- ADDED「演示环境可选刷盘」。

### 受影响的代码
- 新增 overlay 文件；文档

### 用户影响
- 默认启动行为不变。显式加 overlay 才放宽刷盘。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新

## 时间线评估

小：约 0.3 天。

## 风险

- **默认被改成 2** → 缓解：禁止改 docker-compose.yml 主文件。
- **被理解成生产优化** → 缓解：文件名与文档都带「演示/压测」且写崩溃丢失窗口。
