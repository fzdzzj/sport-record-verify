# 提案：补真中间件路径的覆盖缺口（Redis L2 / RocketMQ broker / 全栈 /daily）

## Why

`work/mailbox/PLAN.md` 开头收口清单与 `add-controlled-verify-entrypoint` 验收记录的「未覆盖」行原样记录三条欠账，且都指向同一个病根：**除了 MySQL Mapper 那条 `*IT`，真中间件路径没有任何自动覆盖**。

- **Redis L2 真序列化往返（A）**：`JdkSerializationRedisSerializer` 存 `List<LeaderboardDTO>` 的往返只在 `HierarchicalCacheRedisRoundTripTest` 的「内存 Map 假件」上验过——字节确实经历了 JDK 序列化，但**从未写进真实 Redis**；实操上 `byte[]` 是否真能跨连接存活、TTL 是否真的落盘、连的是不是预期实例（本机 6379 常驻原生 `redis-server.exe` 会抢占，见概览 §5.10）都没有凭据。
- **RocketMQ 真 broker（B）**：`VerifyOutboxRelay`（verify 生产侧）与 `LeaderboardEventConsumer`（leaderboard 消费侧）的订正测试全打在 mock 上，**verify → leaderboard 事件链路从未经过真实 broker**；"发送成功返回 msgId""消费端按 Tag 拿到体"都是靠 stub 答出来的。
- **全栈 `/daily` 端到端（C）**：`GET /api/leaderboard/daily`（TASK-108 新端点，已收为仅 ADMIN）没有自动覆盖。概览 §8 建议把 `smoke-evidence.sh` 的"只抽取不断言"升级为带身份与版本号的机器判据。

**期望状态**：A、B 各补一条可运行的针中间件 Java IT（真 Redis、真 broker，红绿可复现、缺 env 跳过按未覆盖记账），经 `--it` 多 IT 清单一键定向执行；C 因宿主全栈运行门槛本期显式记未覆盖并给出分期判据方向。

## What Changes

- **A** 新增 `LeaderboardL2RedisRoundTripIT`（leaderboard-service，`com.sportverify.leaderboard.config`）：把生产 `CacheConfig#hierarchicalCacheManager` 接到真 RedisConnectionFactory（Redisson），写 `List<LeaderboardDTO>` → 新实例读回 → 断言 equals、`BigDecimal` scale、**TTL≈5min**，并打印 `INFO server` 的 `run_id/tcp_port` 作为实例归属凭据（与 `docker exec` 输出比对，防连错原生 6379 把空容器误判成丢数据）。
- **B** 新增 `RocketMqBrokerRoundTripIT`（leaderboard-service，`com.sportverify.leaderboard.mq`）：用同一套 `DefaultMQProducer` / `DefaultMQPushConsumer`（topic + `VERIFIED||REJECTED` 订阅）在真实 namesrv 上发一条、收一条，断言 `msgId`、Tag、消息体字节、反序列化后 `eventId/recordId/eventType` 一致。为不打扰生产 `leaderboard-consumer-group`，用隔离 topic 与独立 consumer group（broker `autoCreateTopicEnable=true` 随发随建）。
- **--it 扩展**：`mvn-verify.sh` 的 `--it` 把 `-Dtest=` 从单类扩为 `IT_CLASSES` 逗号清单（A + B + 既有 MySQL Mapper IT）。`IT_MODULE` 仍 `leaderboard-service`；新增 `TASK110_IT_*` 进 `IT_ENV_VARS` 并集。**只作用于 `run_it=1` 分支**，常规 `test/verify/package` 路径不读该清单，281 与退出码语义不变。
- **C** 本期**未覆盖 + 分期理由**：判据形态设计为独立 `scripts/smoke/smoke-daily.sh`（宿主六服务 8080–8085 模式下，走登录取 token 带 `X-…` 头请求 `/daily`，并断言返回值里的 rule version 等机器信号）。但跑它需要：六服务宿主拉起（纳宇规则配置、ADMIN 登录、MySQL seed `daily_summary`）全链路可用。本次环境中该全栈宿主机不可立即可得（mapmatch/PostGIS、sharding 是已知遗留，属另一事项），故不交付未经红绿自证的脚本，显式记未覆盖并指明下一期判据方向。

**明确不做**：

- 不引入 Testcontainers / Flyway / Liquibase / failsafe / 任何新 Maven 插件（既有提案共同硬边界）。
- 不改 `src/main` 业务逻辑；不碰 `docker-compose*.yml` 本体、Dockerfile、`web/`、`.github/workflows/ci.yml`、`scripts/db/`、`scripts/verify/mailbox-contract.sh`。
- 本期不落 `smoke-daily.sh`（C 未覆盖，分期）；不修改 `smoke-evidence.sh`（其机器化随 C 一并分期）。
- 不连开发库；MySQL 走 scratch 库；仅连与容器核对过 run_id 的 Redis 实例。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「真中间件路径有自动覆盖」（Redis L2 真往返 / RocketMQ 真 broker / 缺 env 跳过按未覆盖）；MODIFIED「真库端到端测试有确定路径」描述 `--it` 已扩展为多 IT 清单。

### 受影响的代码
- 新增 2 个 IT：`leaderboard-service/src/test/java/com/sportverify/leaderboard/config/LeaderboardL2RedisRoundTripIT.java`、`.../mq/RocketMqBrokerRoundTripIT.java`
- 修改 `scripts/verify/mvn-verify.sh`（仅 `--it` 段多 IT 扩展，常规路径不变）
- 修改 `scripts/verify/env.example`（补 `TASK110_IT_*` 样例）
- 台账：`work/mailbox/PLAN.md`（验收记录）、`work/mailbox/tasks/TASK-110/{spec,handoff}.md`

### 用户影响
- 本地有真 Redis / 真 RocketMQ 时，`bash scripts/verify/mvn-verify.sh --it` 一键覆盖三条真链路；无中间件或缺 env 时均按"跳过=未覆盖"记账，不提升通过数。

### API 变更
- 无。

### 需要迁移
- 无 DB 迁移；A 仅在 scratch 级别使用真中间件；无 API/版本/用户沟通。

## 时间线评估
- A + B：真中间件已在位，约半天（含红绿取证与缺 env 跳过取证）；C：本期排除，视全栈宿主环境另排。

## 风险与缓解

- **连错 Redis 实例**（原生 6379 抢占）→ 缓解：IT 打印 run_id/tcp_port 与 `docker exec` 输出双向核对，回传附实例归属证据。
- **`--it` 扩展影响常规路径** → 缓解：改动严格限 `run_it=1` 分支，`--mode=offline test` 复跑确认 281 不变、退出码语义不变。
- **真 broker topic 与生产消费组互扰** → 缓解：IT 用独立 topic/group + unique 层，不碰 `record-verify-events` 与 `leaderboard-consumer-group`。
- **C 若强行当期交付会产出未经红绿自证的恒绿脚本** → 缓解：按规矩显式记未覆盖 + 分期理由，不交付未验证判别式。

## 备注

- A 的 `TASK110_IT_REDIS_PASSWORD` 为可选（scratch 无口令实例留空）；缺 HOST/PORT 即跳过。
- 红线复演在 IT 类注释内说明（改期望值 / 改订阅 Tag 即红），红绿两条证据都进回传。
- 不 push、不建 PR；未达外部门槛时在台账显式标注。