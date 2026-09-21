# TASK-111 sharding.yaml 的 MySQL host 参数化

## 目标

补齐 TASK-104 handoff「未解决项」第 1 条遗留：`record-service` 数据源走 `sharding.yaml`，其中
`jdbcUrl` 的 host 硬编码 `127.0.0.1`，容器内经 env 覆盖不到 host，容器内连不上 MySQL。
`docker-compose.services.yml` 已预注入 `SHARDING_MYSQL_HOST: mysql` 且注释声称「已参数化」——
本任务把实现补齐，消除注释与实现不一致。

## 约束（硬边界，同主规格）

- 只改白名单 9 文件；不改 `docker-compose.services.yml`（已含注入）、`ShardingDataSourceConfig.java`
  （`loadShardingYaml` 的 `${VAR:default}` 机制已够用）、`env.example`、任何 pom。
- 认可唯一 `scripts/verify/mvn-verify.sh`；退出码 3 = 依赖来源不可判定，不得记通过。
- 不 push、不建 PR、不改 CI；不引入新插件/新依赖。
- 默认值必须保持 `127.0.0.1`（宿主直跑口径零变化）。

## 只改清单

1. `record-service/src/main/resources/sharding.yaml`（jdbcUrl host 占位化）
2. `record-service/src/test/resources/sharding.yaml`（增 `sharding-host` 真变量哨兵）
3. `record-service/src/test/java/com/sportverify/record/config/ShardingDataSourceConfigTest.java`（+2 用例）
4. `spec/changes/add-sharding-host-parameterization/proposal.md`
5. `spec/changes/add-sharding-host-parameterization/tasks.json`
6. `spec/changes/add-sharding-host-parameterization/specs/sport-record-verify/spec-delta.md`
7. `work/mailbox/tasks/TASK-111/spec.md`
8. `work/mailbox/tasks/TASK-111/handoff.md`
9. `work/mailbox/PLAN.md`

## 具体改动

1. **main sharding.yaml** `jdbcUrl` 行：`jdbc:mysql://127.0.0.1:${MYSQL_PORT:3306}/record_db?...` →
   `jdbc:mysql://${SHARDING_MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/record_db?...`。
2. **单测加固**：
   - test/resources/sharding.yaml 增一行哨兵 `sharding-host: ${SHARDING_MYSQL_HOST:127.0.0.1}`；
   - `ShardingDataSourceConfigTest` 新增 2 用例：① 替换非残留、结果与当前 env 或默认 127.0.0.1 一致；
     ② 默认分支未设保留 127.0.0.1 / 设 env 取该值。
   - **静态 mock 说明**：Mockito 禁止 `mockStatic(System.class)`（类加载无限循环保护），故不写
     `mockStatic(System)` 用例；红绿取证改为**运行级注入 `SHARDING_MYSQL_HOST` + 临时期望编辑**复现。
3. 变更三件套 `spec/changes/add-sharding-host-parameterization/`（proposal/tasks.json/spec-delta，EARS）。
4. 台账 `work/mailbox/tasks/TASK-111/{spec,handoff}.md`、`work/mailbox/PLAN.md` 追加验收记录。

## 必须做的取证（先红后绿，两条都进回传）

1. **红（单测）**：注入 `SHARDING_MYSQL_HOST=mysql` 运行，同时把用例②临时改成固定期望
   `127.0.0.1` → 断言红 `expected: <true> but was: <false>` / BUILD FAILURE；还原绿。
2. **红（容器口径）**：`sport-verify_sport-verify-net` 网络内一次性 mysql 客户端 `-h 127.0.0.1`
   → 连接失败非 0（证容器内是服务名寻址，非宿主回环）；`-h mysql` → 绿退出 0，能 `USE record_db` 查询。

## 验收命令（唯一口径）

```bash
bash scripts/verify/mvn-verify.sh --mode=offline test      # BUILD SUCCESS，record 78→80、合计 282→283
# 构建产物：record-service/target/classes/sharding.yaml 含 ${SHARDING_MYSQL_HOST:127.0.0.1}
docker compose -f docker-compose.yml -f docker-compose.services.yml config   # 退出 0，record env 含 SHARDING_MYSQL_HOST: mysql
# 容器内可达：同网络 mysql:8.0 mysql -h mysql -P 3306 -uroot -proot -e "USE record_db; SELECT ..." 退出 0
bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106            # 契约自证
```

## 完成定义

`handoff.md` 写入：只改清单（纯路径） / 环境核实（网络名、mysql 容器名、口令、record_db 在位） /
红绿取证原文（断言+行号、退出码、容器两端输出）/ 实跑结论（offline 模块汇总 283、compose config、
target/classes、容器内可达输出）/ 合同自证退出码与残余项 / 台账位置。