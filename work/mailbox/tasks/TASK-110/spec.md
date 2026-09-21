# TASK-110 真中间件路径的覆盖缺口（Redis L2 真往返 / RocketMQ 真 broker / 全栈 /daily 分期）

## 目标

补 PLAN 收口清单记录的三条真中间件路径缺口，且不破坏既有硬边界：

- **A**：`JdkSerializationRedisSerializer` 存 `List<LeaderboardDTO>` 的往返从「内存 Map 假件」升级为**真 Redis** 凭据。
- **B**：verify → leaderboard 事件链路的**真 RocketMQ broker** 生产/消费往返凭据。
- **C**：TASK-108 新端点 `GET /api/leaderboard/daily` 的全栈 `/daily` 端到端——本期经论证**显式记未覆盖 + 分期理由**，不交付未红绿自证的脚本。

## 约束（硬边界，同主规格）

- 沿用既有 IT 模式：类名 `*IT`（surefire 不收集）、env 变量命名 `TASK110_IT_*`、缺任一必填 env 即 `Assumptions` 跳过（**跳过按未覆盖记账，不得计入通过**）。
- **禁止引入** Testcontainers / Flyway / Liquibase / failsafe / 新 Maven 插件。
- 不改 `src/main` 业务逻辑；不碰 `docker-compose*.yml` 本体、Dockerfile、`web/`、`.github/workflows/ci.yml`、`scripts/db/`、`scripts/verify/mailbox-contract.sh`。
- MySQL 走 scratch 库；Redis 只连与 `docker exec` 核对过 `run_id` 的实例。
- 不 commit / push / 建 PR。

## 只改/新建文件

- 新建 `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/LeaderboardL2RedisRoundTripIT.java`
- 新建 `leaderboard-service/src/test/java/com/sportverify/leaderboard/mq/RocketMqBrokerRoundTripIT.java`
- 改 `scripts/verify/mvn-verify.sh`（仅 `--it` 段多 IT 扩展；常规路径行为不变）
- 改 `scripts/verify/env.example`（补 `TASK110_IT_*` 样例）
- 新建 `spec/changes/add-middleware-it-coverage/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`
- 新建本 `spec.md` 与 `handoff.md`
- 改 `work/mailbox/PLAN.md`（按收口清单追加验收记录）

## A：Redis L2 真序列化往返（真 Redis）

过程：用 `Redisson.create(Config)` 建 `RedissonClient` → `new RedissonConnectionFactory(redisson)`，走生产 `@Bean CacheConfig#hierarchicalCacheManager(factory)`，把 `List<LeaderboardDTO>` 写给独立大数用户键；再用**另一个全新 CacheManager 实例**（L1 空）按 `@Cacheable` 语义从真 Redis 读回。

断言：写读 `equals`、`BigDecimal` scale 保两位，TTL≈300s（5 分钟配置），并打印 `INFO server` 的 `run_id/tcp_port` 作为实例归属凭据。

## B：RocketMQ 真 broker 往返

过程：用生产同一套 `DefaultMQProducer` / `DefaultMQPushConsumer`，在真 namesrv 上发一条 `VerifyEventDTO`（Tag `VERIFIED`），消费侧订阅 `VERIFIED||REJECTED`，断言 `msgId`、Tag、消息体字节、反序列化后 `eventId/recordId/eventType` 一致。

隔离：每次运行用**独立 topic 后缀 + 独立 consumer group**（broker `autoCreateTopicEnable=true` 随发随建），不触碰生产 `record-verify-events` 与 `leaderboard-consumer-group`，避免上一轮残留被 `LAST_OFFSET` 竞态拉到。

## C：全栈 `/daily` 端到端（本期未覆盖）

判据形态：独立 `scripts/smoke/smoke-daily.sh`（宿主六服务 8080–8085 模式，登录取 token + 携带身份头请求 `/daily`，断言返回里的 rule version 等机器信号）。因宿主全栈拉起门槛本环境不具备（mapmatch/PostGIS/sharding 为已知遗留，属另一事项范围），本期**不交付**该脚本，显式记未覆盖并给分期理由。

## 必须做的取证（每条判别式先红后绿，两条都进回传）

1. Redis IT 红：改 scale 期望 2→3 即红（`expected: 3 but was: 2`）；还原即绿。
2. RocketMQ IT 红：订阅 Tag 改为与发送不匹配（只订阅 `SUBMITTED`）→ 收不到 → 超时 `assertNotNull` 红；还原即绿。
3. 缺 env 跳过：不设 `TASK110_IT_*` 时 `--it` 的 Redis/RocketMQ 两条 `Skipped:1`（按未覆盖记账），MySQL IT 仍绿。
4. 常规路径不变：`bash scripts/verify/mvn-verify.sh --mode=offline test` 复跑仍为 281。

## 验收命令（唯一口径，经 `mvn-verify.sh`）

```bash
bash scripts/verify/mvn-verify.sh --mode=offline test     # 281 不变
bash scripts/verify/mvn-verify.sh --it                    # 真中间件在位时 3 类 5 例全绿；缺 env 时跳过
bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106   # 契约自证，退出 0
```

## 完成定义

`handoff.md` 写入：只改清单（file:line）/ 环境核实（Redis 实例归属 run_id 对比、端口结论、scratch 库名）/ 红绿取证原文（含退出码）/ 缺 env 跳过原文 / 实跑结论（offline 281、`--it` 真跑、contract 退出码）/ 台账位置 / 未决（C 分期未覆盖）。正文 ≤300 字，关键输出贴原文。