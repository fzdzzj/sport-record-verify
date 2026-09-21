# 功能冒烟脚本（scripts/smoke/）

规则灰度发布链路（创建版本 → 采样判定 → 秒级回滚 → 全量发布）的端到端冒烟，
覆盖 Redis 失效广播、MQ 异步判定、契约错误码与库表终态证据抽取。

## 前置条件

1. 中间件就绪：在仓库根目录执行 `docker compose up -d`，`docker compose ps` 中
   nacos / mysql / redis / rocketmq / postgis 等容器为 `healthy`。
2. 六个服务已启动（各自端口）：

   | 端口 | 服务 |
   | --- | --- |
   | 8080 | gateway-service |
   | 8081 | user-service |
   | 8082 | record-service |
   | 8083 | verify-service |
   | 8084 | leaderboard-service |
   | 8085 | mapmatch-service |

3. 全部脚本在**仓库根目录**运行（`bash scripts/smoke/xxx.sh`）；
   curl、awk、sed、date（GNU 工具）可用（Git Bash 自带）。
4. 可覆盖的环境变量（均有默认值，仓库内无本机硬编码）：

   | 变量 | 默认值 | 说明 |
   | --- | --- | --- |
   | `BASE_URL` | `http://127.0.0.1:8080` | 网关入口 |
   | `LOG_DIR` | `logs` | 中间产物/日志输出目录（logs/ 已被 .gitignore 忽略，不入库） |
   | `REDIS_CONTAINER` | `sport-verify-redis` | 校验 Redis 容器名 |
   | `MYSQL_CONTAINER` | `sport-verify-mysql` | 校验 MySQL 容器名 |
   | `RECORD_IDS` | `19497,19498,19499,19500` | smoke-evidence.sh 查询的记录 ID |
   | `SMOKE_DAILY_PHONE` / `SMOKE_DAILY_PASSWORD` | `13900009999` / `smoke-daily-116` | smoke-daily.sh 登录账号（须 ADMIN，否则 `/daily` 被网关 403） |
   | `SMOKE_DAILY_EXPECT_TOP` | `88.05` | smoke-daily.sh 期望的 top-1 距离（红绿改动点：改错即断言失败） |
   | `SMOKE_DAILY_DATE` | （无，默认取 3 天前的过去日期） | smoke-daily.sh 判定日期（缺省为隔离岛日期，避免与结算的 CURDATE() 冲突） |

## 执行顺序

| 步骤 | 脚本 | 覆盖链路 |
| --- | --- | --- |
| 0（前置） | `bash scripts/smoke/smoke-schema.sh` | 登录路径 schema 探测（错误凭据）；**不依赖**灰度版本号，须在步骤 1 前通过 |
| 1 | `bash scripts/smoke/smoke-a.sh` | 创建版本 → 调灰度 10 → 捕获 Redis 失效广播 → 生成 5.8 m/s 轨迹 |
| 2 | `bash scripts/smoke/smoke-b.sh` | 提交记录 → MQ → verify 判定 → 灰度命中(PASSED)/未命中(REJECTED) |
| 3 | `bash scripts/smoke/smoke-cd.sh` | gray=0 秒级回滚立即生效 → activate 全量发布旧版退役 |
| 4 | `bash scripts/smoke/smoke-negative.sh` | 规则版本接口负向用例（4002/4003/4004) |
| 5 | `bash scripts/smoke/smoke-evidence.sh` | 抽取库表终态 + verify 日志广播记录（验收证据） |
| 6 | `bash scripts/smoke/smoke-daily.sh` | 全栈 `/daily` 端到端（登录取 token → 携带身份头 → 断言当日快照 top 距离） |

> 步骤 0 为 schema 前置，与灰度版本无关；步骤 1-3 有依赖（复用同一版本号与轨迹文件），须按顺序执行；4、5、6 可在其后随时复跑。
> 步骤 6 需**六中间件 + 六服务**全就绪，且存在一个可登录的 ADMIN 账号与 `smoke-daily` 可写的
> `record_db`（具体判定见下方「步骤 6」小节）。

### 步骤 0：`smoke-schema.sh` 四分判据

对 `POST $BASE_URL/api/auth/login` 使用错误密码（默认手机号 `13900001111` / `wrongpass`，可用 `SMOKE_SCHEMA_PHONE` / `SMOKE_SCHEMA_PASSWORD` 覆盖）；不加内部凭证头、不依赖 jq。

| 结果 | 信号 | 处理 |
| --- | --- | --- |
| **通过** | HTTP **401** 且 body `code=1001` | 凭据错误路径已跑通，说明登录 SELECT 可用（schema 对齐） |
| **硬失败** | HTTP **500** 或 `code=9999` | schema/系统错误（如缺 `role` 列）；先 `bash scripts/db/migrate.sh` |
| **服务未就绪** | 连接失败 / HTTP **502** / **503** | 非 0 退出；先起网关与 user-service，**不是**缺列 |
| **账号锁定** | HTTP **403** 或 `code=1002` | 非 0 退出；换未锁定手机号，**不是**缺列，也**不能**当通过 |

### 步骤 6：`smoke-daily.sh` 全栈 /daily 端到端

链路：登录取 token（user-service）→ 携带 `Authorization: Bearer` 经网关 → leaderboard `/daily`
→ 断言响应结构 + 抽取「当日快照 top 名次距离」机器信号。前提为六中间件 + 六服务全就绪，
且登录号是 ADMIN（默认 `13900009999` / `smoke-daily-116`，可用相关 `SMOKE_DAILY_*` 变量覆盖；
`/leaderboard/api/leaderboard/daily` 在网关 `app.auth.admin.paths` 内，非 ADMIN 会被 403）。

判据语义（"rule version 机器信号"）：`/daily` 返回 `leaderboard_daily_summary` 当日报表行，
无字面 `ruleVersion` 字段，故以**隔离岛日期下 top-1 距离**作可断言、可红绿的机器信号——
该沉淀行由 verify→leaderboard 结算管线（`settleAndReconcile` → `upsertFromActiveContributions`）
写出，top 距离唯一取自规则校验通过的里程。

| 结果 | 退出码 | 信号 | 处理 |
| --- | --- | --- | --- |
| **通过** | 0 | HTTP 200 + `code=0` + data 数组 + top-1 距离 == `SMOKE_DAILY_EXPECT_TOP` | 全栈 /daily 链路通 |
| **断言失败** | 1 | HTTP 非 200 / `code!=0` / 非数组 / top-1 ≠ 期望（含登录未取到 `accessToken`） | 逐行看日志，先确认数据与账号 |
| **服务未就绪** | 3 | curl 连接失败 / HTTP 502/503 / MySQL 预置失败 | 先起六中间件 + 六服务，不是断言失败 |

> 隔离岛：判定日期默认取「3 天前的过去日期」，结算管线只写 `CURDATE()`，故该日快照行仅由
> smoke-daily 预置（先清后插），top-1 结果确定。改动 `SMOKE_DAILY_EXPECT_TOP` 不改变预置的
> `SEED_TOP`（固定 `88.05`），故把期望值改错必然红、还原即绿。

## 预期输出

- **smoke-schema.sh**：`通过: HTTP 401 + code=1001`；若 500/9999 则硬失败；403/1002 提示换号；502/503/连接失败提示服务未就绪。
- **smoke-a.sh**：Redis 订阅输出含 `verify:rules:invalidate` 频道与消息 `1`（versionId=1）；
  `smoke-track.json` 生成成功（约 4.4KB）。
- **smoke-b.sh**：`smoke-verdicts.txt` 追加
  `hit=<rid> verdict=1 expect=1` 与 `miss=<rid> verdict=2 expect=2`。
  （verdict：1=PASSED，2=REJECTED；userId=105 采样 5<10 命中灰度快照 6.6，
  userId=150 采样 50≥10 走基线 5.5，同一 5.8 m/s 轨迹。）
- **smoke-cd.sh**：回滚广播捕获含 versionId=1；`verdicts.txt` 追加
  `rollback=<rid> verdict=2 expect=2`、`full=<rid> verdict=1 expect=1`。
- **smoke-negative.sh**：N1→4003、N2→4004、N3→4004、N4→4002。
- **smoke-evidence.sh**：`rule_version` 终态（v-smoke-1 ACTIVE gray_ratio=100）、
  四条 `verification_result`（verdict 与 verdicts.txt 一致）、
  verify 日志含「规则缓存失效广播已发送：versionId=1」「收到规则缓存失效广播，本地路由缓存已清」。
- **smoke-daily.sh**：`登录成功：HTTP 200，token 已获取`；
  `断言通过: HTTP 200 + code=0 + data 为数组`；
  `断言通过: top-1 距离 88.05 == 期望 88.05`；最后 `== smoke-daily 通过 ==`（退出 0）。
  把 `SMOKE_DAILY_EXPECT_TOP` 改为错误值（如 `99.99`）则输出 `断言失败: top-1 距离 88.05 ≠ 期望 99.99`（退出 1）；服务未起则 `未就绪`（退出 3）。

## 失败判定标准

任一情况判定冒烟失败：

0. `smoke-schema.sh` 未通过（500/9999 硬失败，或 403/1002 锁定，或 502/503/连接失败未就绪，或非 401+1001）。
1. 创建版本/调灰度/回滚/全量请求返回非 0 错误码或接口 5xx（网关 503 视为服务未就绪，先查服务）。
2. Redis 订阅在预期时间内未捕获 `verify:rules:invalidate` 广播（回滚秒级失效未生效）。
3. 任一条目的判定 verdict 与 expect 不符（如命中用户被基线拒绝、回滚后仍命中灰度）。
4. 负向用例未返回契约错误码（4002/4003/4004），或误返回业务成功。
5. 轮询 25s 未收敛出终态判定（MQ 消费或校验链路异常）。

修复冒烟失败后，重复受影响步骤即可（步骤 1 的版本号 `v-smoke-1` 需先人工清理或改版本号重跑）。
