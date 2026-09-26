# TASK-141：提交链路连接池容量单因素对照（含池生命周期并发前置修复）

## 目标

按 `spec/changes/measure-submit-pool-capacity/` 三件套执行一次完整作业：
先以确定性交错判别修复 `TimingHikariDataSource` 关闭与首次懒建池的并发窗口（红→绿），
修复通过门槛后在同一 jar 上做单因素池容量对照（仅轮换 `MYSQL_POOL_SIZE` 10/20，
A-B-B-A 至多四轮 c100×2000），按两臂各两次的证据给三选一结论。**不修改生产默认池
容量，不实施其他任何性能优化。**

规范来源：`spec/changes/measure-submit-pool-capacity/`（proposal.md / tasks.json /
spec-delta.md）。

## 开工基线

- HEAD 应为 `8380e11d0ffde4342b1fdc9ff828adbbe191d197`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、
  `.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`。
- TASK-138~140 原始 raw（head/dbfoot/split/rpt1/rpt2/connwait）不得覆盖；本轮四轮
  使用独立 label（pool10-r1 / pool20-r1 / pool20-r2 / pool10-r2）。

## 门槛与停止条件（要点）

1. 安全前置：并发交错测试先在未修复实现取**行为红**（非编译红、非睡眠碰运气）；
   最小修复后单测/构建转绿才允许压测。
2. 实验门槛：冻结唯一 jar 的 SHA-256；磁盘、MySQL `max_connections`、积压、服务健康
   核实后才开跑；每轮固定预热/重启/排空/采样程序；任一门槛失败停止并记未覆盖。
3. 单因素：两臂仅 `MYSQL_POOL_SIZE` 不同；`tx-timing-enabled` 四轮均 true；不改
   JVM/SQL/索引/事务/MQ。
4. 结论三选一：值得进一步验证 / 不确定 / 不推荐；connWait 不称纯池排队；诊断开启
   数字不得当作生产默认关闭状态的收益。
5. 收口：报告 + 机器摘要 + 三件套勾选 + TASK-141 台账 + 本地提交；不 push、不建 PR。

## 负载与环境

- Shell：Git Bash（MINGW64）；命令行禁止中文；提交用 `git commit -F` UTF-8 文件。
- MySQL 容器 3307；中间件 `docker compose up -d nacos mysql redis rocketmq-namesrv
  rocketmq-broker`；gateway/user/verify 四轮共用不重启（user/verify 需 `MYSQL_PORT=3307`）。
- record 每轮重启：`MYSQL_PORT=3307` + `--record.submit.tx-timing-enabled=true` +
  每轮独立 GC 日志，仅轮换 `MYSQL_POOL_SIZE`。
- 逐轮驱动脚本在仓库外临时文件（不入库）；逐轮采样数据入 `docs/perf/data/raw/`
  （既有忽略目录）与 `logs/`。

## 判别式

- 红：`TimingHikariDataSourceWiringTest` 的两条交错判别在未修复实现上失败于
  「close 完成后包装类不得再创建内层池」（pool 字段非空），两重载各一次。
- 绿：同测试类 10/10；`mvn-verify.sh --mode=offline --pl record-service test` rc=0
  （103/0/0/0）；`package` rc=0。
- 实验：四轮全部 load 退出码 0、2000/0/0、排空到 0、四服务健康复查 200 才进下一轮。

## 停止与恢复

四轮完成后停全部四个 Java 服务；诊断仅以启动参数存在过、未写入任何配置；池默认
`${MYSQL_POOL_SIZE:10}` 未动；未清库（主表 32382→40422，track_point 约 +241 万行）。
