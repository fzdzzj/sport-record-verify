# 后端优化清单（主 agent 亲审，按严重度分级）

范围：8 个 Maven 模块 + docker-compose / sql / CI / 网关与鉴权配置。证据均落到 文件:行号。
P0=必修（安全/数据丢失）；P1=应修（可靠性/性能）；P2=建议（规范/规模相关）。

## P0 必修

### F01 鉴权默认关闭时，X-User-Id 可被外部伪造（安全）
- 位置：gateway-service AuthGlobalFilter.java:79；application.yml:106（app.auth.enabled 默认 false）
- 证据：enabled=false 时直接 `chain.filter(exchange)`，**不剥离**外部传入的 X-User-Id/X-Role 头；下游服务只认这两个头认定身份 → 任何人自带 `X-User-Id: 1` 即可冒充任意用户，连 token 都不需要。
- 改法：① enabled=false 的降级路径也必须 remove X-User-Id/X-Role 后再透传；② 提供 profile 化默认（prod 强制 true）；③ README 明确「上线前必须开启」检查项。

### F02 中间件弱口令 + 全端口绑定宿主机（安全）
- 位置：docker-compose.yml:43（MYSQL_ROOT_PASSWORD=root）、:136（POSTGRES_PASSWORD=postgres）、Redis 无密码（:65-76）、Nacos NACOS_AUTH_ENABLE=false（:23）、Grafana admin/admin（:181-182）；3306/6379/8848/9090/3000 全部端口映射宿主机。
- 改法：口令改从 .env 注入且 .env 提供强默认占位 + .gitignore 校验；非必要端口绑 127.0.0.1；compose 文件头部加「仅限本地」醒目警告。

## P1 应修

### F03 VERIFIED/REJECTED 事件发送失败即永久丢失（一致性）
- 位置：verify-service VerifyEventProducer.java:57-60（catch 后仅 log）
- 影响：判定 PASSED 但事件丢失 → leaderboard_contribution 无锚点行，结算任务以贡献表为权威也补不回来 → 榜单永久缺分。record→verify 方向有降级补偿（VerifyDegradeService），verify→leaderboard 方向没有。
- 改法：verify_db 加本地消息表（outbox），同事务落「事件待发」行 + 定时 relay 重发；或改 RocketMQ 事务消息。

### F04 好友锁在事务内，锁释放早于事务提交（并发）【已裁定维持权衡、不修（2026-09-23 指导侧，PLAN 裁定记录）】
- 位置：user-service FriendService.java:151-191（@Transactional 方法内加 Redisson 锁，finally 释放时事务尚未提交）
- 影响：A accept 释放锁→B createRequest 拿到锁→读不到未提交的 friendship→建出冗余申请单（friendship 有唯一键兜底但申请单状态错乱）。
- 改法：锁外提一层（Controller/Facade 先拿锁，再调 @Transactional 方法），或编程式事务模板包在锁内。
- 裁定：ADR-0009 已把该顺序记为已知权衡并有守卫测试；窗口内失效路径均被「旧状态拒绝式守卫 + 唯一键 + 乐观流转」兜住，无正确性缺陷实证，维持权衡。出现可复现缺陷时按缺陷工单重开。

### F05 mapmatch 逐点一次 SQL（性能）
- 位置：mapmatch-service MapMatchService.java:110-126（每个采样点一次 ST_DWithin 往返）
- 影响：50 个采样点 = 50 次 DB RTT；判定链路 P95 被它主导。
- 改法：先按轨迹 bbox 一次查出候选边（ST_Intersects 缓冲区），Java 内存中逐点算垂距；或 ST_Collect 一次查询。

### F06 好友榜全量拉 ZSet 内存过滤（规模性性能）
- 位置：leaderboard-service LeaderboardService.java:250-251（reverseRangeWithScores(0,-1)）
- 影响：榜上 10 万用户时每次好友榜查询拉全榜进内存。
- 改法：Redis 侧 ZRANGEBYSCORE 分批 + 提前裁剪，或维护好友榜增量结构；短期至少限制 size 并用 ZREVRANGE 前 K 后过滤的迭代取数。

### F07 Feign 默认客户端无连接池（性能）
- 位置：pom.xml 未引入 feign-httpclient5/okhttp；各服务 application.yml 无 httpclient 配置
- 影响：服务间调用每次新建 TCP 连接，5k QPS 目标下握手开销与端口耗尽风险。
- 改法：引入 Apache HttpClient5 Feign 客户端 + 连接池配置（max-total/per-route/ttl）。

### F08 actuator 对外暴露过宽（安全/运维）
- 位置：各服务 application.yml `include: health,info,prometheus,metrics` + `show-details: always`；网关白名单含 `/actuator/**`（gateway application.yml:114）
- 影响：health 详情泄漏 DB/Redis 组件状态；metrics 对外可查。
- 改法：网关白名单收窄到 `/actuator/health`（或整体移除、监控走内网直连）；show-details 改 when-authorized。

### F09 自建 DLQ 与 RocketMQ 原生重试双轨 + 重试计数键泄漏（可靠性/工程）
- 位置：verify-service VerifyEventConsumer.java:52-54,225-233；leaderboard-service LeaderboardEventConsumer.java:55-57,215-224（两处复制同构代码）
- 证据：返回 RECONSUME_LATER 时 broker 本身会按退避重投（默认 16 次后进 %DLQ%），代码又用 `verify:retry:{eventId}` 自计数 3 次投自建 topic；retryKey 的 AtomicLong **无 TTL**，随事件量无限堆积。
- 改法：二选一（推荐用 broker 原生 maxReconsumeTimes + %DLQ%）；至少给 retryKey 设 24h TTL；两份消费者公共逻辑抽到 common。

### F10 JWT/内部接口密钥硬编码兜底（安全）
- 位置：user-service JwtUtil.java:48；gateway application.yml:110；common InternalApiAuthFilter.java:41；api InternalApiFeignInterceptor.java:19
- 改法：生产 profile 下无默认值、缺失即启动失败（仅 local profile 保留演示默认）；InternalApiAuthFilter 改常量时间比较（MessageDigest.isEqual）。

## P2 建议

### F11 登录锁定可被滥用做账号 DoS：auth:lock:{phone} 按手机号锁 15min，攻击者对任意手机号试错 5 次即锁定机主。改法：锁定叠加 IP 维度或改滑窗限流。（AuthService.java:264-288）
### F12 无登出/吊销：access token 签发后至过期前不可吊销。改法：access jti 黑名单（TTL=剩余时效）或文档声明接受 15min 窗口。
### F13 判定结果缓存多实例不一致窗口：Caffeine 单层 1min 无跨实例失效，多实例并发重判可能重复发事件（消费端幂等兜底，可接受；建议文档化或接入二级缓存失效广播）。（VerifyService.java:116）
### F14 规则链不短路：R1 已 HARD 命中仍执行 R5 远程调用。规范要求「收集全部命中」时维持现状；若只要判定结果可短路省一次 Feign。（VerifyEngine.java:78-83）
### F15 点赞对账全表扫 + 逐记录查询：reconcileLikeCounts 对每 record_id 一次 SELECT。改法：一条 GROUP BY 聚合 SQL 出全部计数，成员集只重建有漂移的。（RecordLikeService.java:263-291）
### F16 点赞 flush 吞吐上限 200/5s：高峰积压。改法：批大小可配 + 队列长度指标告警。（RecordLikeService.java:80-82）
### F17 readCount 回源无防击穿：缓存 miss 并发回源 DB。改法：单 flight（Caffeine 或 SETNX 短锁）。（RecordLikeService.java:329-339）
### F18 VerifyDegradeService 扫描缺索引：WHERE status=? AND created_at<? 无复合索引，全表扫。改法：sport_record 加 idx_status_created(status, created_at)。（VerifyDegradeService.java:106-110；sql/02-record-db.sql:27）
### F19 Sentinel 兜底路由不全：ROUTE_IDS 只有 record/user/verify，漏 auth/leaderboard/mapmatch/admin 四个路由的代码兜底。（SentinelGatewayRuleConfig.java:46-48）
### F20 服务未容器化：compose 只编排中间件，6 个服务靠宿主机 java -jar；缺 Dockerfile、缺服务级编排与健康检查。改法：multi-stage Dockerfile + compose 服务层（可选 profile）。
### F21 CI 缺口：无静态检查（spotless/checkstyle）、无依赖漏洞扫描（OWASP dependency-check）、无镜像构建。改法：按需增补 CI 步骤。
### F22 VerifyService.verify 同一 recordId 并发重入无互斥：双判定重复回调依赖 3003 冲突重试收敛。改法：可配 Redisson 锁或文档化接受。（VerifyService.java:79-118）

## 维度总评
- 架构：模块边界清晰、契约/实现分离到位；主要欠账是「事件可靠性」（F03/F09）与「锁-事务顺序」（F04）。
- 并发：幂等/乐观锁/防重设计普遍扎实；个别规模性热点（F05/F06/F15）。
- 工程规范：注释与 ADR 文化优秀；CI、容器化、密钥治理是短板（F02/F10/F20/F21）。
- 安全：最大风险集中在默认配置（F01/F02/F08/F10），都是「本地演示默认」遗留。
