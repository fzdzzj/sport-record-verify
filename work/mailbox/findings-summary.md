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

### F03 VERIFIED/REJECTED 事件发送失败即永久丢失（一致性）【仍在（outbox 组件已建但从未接线，2026-09-23 核实 TASK-128）】
- 位置：verify-service VerifyEventProducer.java:57-60（catch 后仅 log）
- 影响：判定 PASSED 但事件丢失 → leaderboard_contribution 无锚点行，结算任务以贡献表为权威也补不回来 → 榜单永久缺分。record→verify 方向有降级补偿（VerifyDegradeService），verify→leaderboard 方向没有。
- 改法：verify_db 加本地消息表（outbox），同事务落「事件待发」行 + 定时 relay 重发；或改 RocketMQ 事务消息。
- 核实（TASK-128，2026-09-23）：a048745（实为 TASK-102 产物，非 TASK-108）只新增了 outbox 组件文件（entity/mapper/relay/事务写入器 + 2 测试），VerifyService（:109/:234）与 VerifyEventProducer（:53-60 直发 + catch 吞异常）从未接线；全仓无代码写 verify_event_outbox 行，sql/ 无该表 DDL，运行日志实证表不存在（logs/verify.out:84）。relay 重发逻辑本身完整但扫永远空表。TASK-102 handoff 所称「VerifyService 改调 persistResultAndEvent」与提交事实不符。差距明细见 tasks/TASK-128/handoff.md。

### F04 好友锁在事务内，锁释放早于事务提交（并发）【已裁定维持权衡、不修（2026-09-23 指导侧，PLAN 裁定记录）】
- 位置：user-service FriendService.java:151-191（@Transactional 方法内加 Redisson 锁，finally 释放时事务尚未提交）
- 影响：A accept 释放锁→B createRequest 拿到锁→读不到未提交的 friendship→建出冗余申请单（friendship 有唯一键兜底但申请单状态错乱）。
- 改法：锁外提一层（Controller/Facade 先拿锁，再调 @Transactional 方法），或编程式事务模板包在锁内。
- 裁定：ADR-0009 已把该顺序记为已知权衡并有守卫测试；窗口内失效路径均被「旧状态拒绝式守卫 + 唯一键 + 乐观流转」兜住，无正确性缺陷实证，维持权衡。出现可复现缺陷时按缺陷工单重开。

### F05 mapmatch 逐点一次 SQL（性能）【仍在（2026-09-23 核实 TASK-130）】
- 位置：mapmatch-service MapMatchService.java:110-126（每个采样点一次 ST_DWithin 往返）
- 影响：50 个采样点 = 50 次 DB RTT；判定链路 P95 被它主导。
- 改法：先按轨迹 bbox 一次查出候选边（ST_Intersects 缓冲区），Java 内存中逐点算垂距；或 ST_Collect 一次查询。
- 核实（TASK-130，2026-09-23）：行号 110-126 逐字吻合（逐点循环 :68-75 → :112-117 每点一次 SQL）。
  量化上界订正：往返次数 = 采样点数，上限由 max-sampled-points=200 决定（MatchProperties.java:18、
  mapmatch application.yml:47），**最坏 200 次 RTT/请求**（原文「50」为示意值非配置上界）；
  该调用在 R5 同步关键路径上（R5OffRoadRule.java:64/68）。候选方案 S1（一次 bbox/折线预筛）的
  语义等价性有代码级证明（:118 best 初值 = 半径 + :121 单调截断 → 超半径候选不改变结果）。
  离线判别式现成（MapMatchServiceTest.java:36 mock JdbcTemplate，:61/78/93 五参打桩）。
  **TASK-103 handoff 声称已批量预筛（fetchCandidateEdges）与事实不符**：`git log --all -S` 仅命中
  `6650ae3`（台账提交自身），当前代码无该方法。详见 tasks/TASK-130/handoff.md 第 5 节。

### F06 好友榜全量拉 ZSet 内存过滤（规模性性能）【仍在（2026-09-23 核实 TASK-130）】
- 位置：leaderboard-service LeaderboardService.java:250-251（reverseRangeWithScores(0,-1)）
- 影响：榜上 10 万用户时每次好友榜查询拉全榜进内存。
- 改法：Redis 侧 ZRANGEBYSCORE 分批 + 提前裁剪，或维护好友榜增量结构；短期至少限制 size 并用 ZREVRANGE 前 K 后过滤的迭代取数。
- 核实（TASK-130，2026-09-23）：**行号订正为 :259-260**（原文 250-251 已漂移，该处现为好友列表 Feign 调用续行）；
  全量拉在 `reverseRangeWithScores(OVERALL_ZSET_KEY, 0, -1)`（:260），方法区间 :246-271。
  放大因素订正：`topFriends` **无 @Cacheable**（对比 topOverall :227 有 L1+L2），故每请求都付全量成本；
  好友数由 FRIEND_FETCH_SIZE=1000（:77）截断，单请求 size 上界 1000（:210）。
  分批取数的 rank 语义等价（assemble 的 rank = 过滤后序号 :285）。离线判别式现成
  （LeaderboardServiceTest.java:79 mock ZSetOperations，:254 已为好友榜打桩 (KEY,0,-1)）。
  附带发现：`listFriends(page=1, size=1000)`（:249）只取第一页 → 好友 >1000 的用户好友榜静默截断。
  **TASK-103 handoff 声称已改分批（FRIEND_SCAN_BATCH）与事实不符**，当前代码无该常量。

### F07 Feign 默认客户端无连接池（性能）
- 位置：pom.xml 未引入 feign-httpclient5/okhttp；各服务 application.yml 无 httpclient 配置
- 影响：服务间调用每次新建 TCP 连接，5k QPS 目标下握手开销与端口耗尽风险。
- 改法：引入 Apache HttpClient5 Feign 客户端 + 连接池配置（max-total/per-route/ttl）。

### F08 actuator 对外暴露过宽（安全/运维）【部分收口，网关侧残留（2026-09-23 核实 TASK-130）】
- 位置：各服务 application.yml `include: health,info,prometheus,metrics` + `show-details: always`；网关白名单含 `/actuator/**`（gateway application.yml:114）
- 影响：health 详情泄漏 DB/Redis 组件状态；metrics 对外可查。
- 改法：网关白名单收窄到 `/actuator/health`（或整体移除、监控走内网直连）；show-details 改 when-authorized。
- 核实（TASK-130，2026-09-23）：**主体已收口，网关自身残留一处**。白名单已收窄为 `/actuator/health`
  （gateway application.yml:120，d89fe15）；5 个后端服务 `show-details` 均为 `never`
  （user:96 / leaderboard:118 / mapmatch:73 / verify:175 / record application.properties:89）——
  但 **gateway application.yml:158 仍为 `always`**：TASK-125 spec 目标写「六个服务 always→never」，
  其只改文件只列了 5 个后端服务 + 网关 whitelist 一行，该行遂成遗漏；而 `/actuator/health`
  恰在网关白名单内且免 token → 匿名可读网关 health 组件明细。一行可修（P2，建议随下一轮治理面变更收尾）。

### F09 自建 DLQ 与 RocketMQ 原生重试双轨 + 重试计数键泄漏（可靠性/工程）【仍在（2026-09-23 核实 TASK-128）】
- 位置：verify-service VerifyEventConsumer.java:52-54,225-233；leaderboard-service LeaderboardEventConsumer.java:55-57,215-224（两处复制同构代码）
- 证据：返回 RECONSUME_LATER 时 broker 本身会按退避重投（默认 16 次后进 %DLQ%），代码又用 `verify:retry:{eventId}` 自计数 3 次投自建 topic；retryKey 的 AtomicLong **无 TTL**，随事件量无限堆积。
- 改法：二选一（推荐用 broker 原生 maxReconsumeTimes + %DLQ%）；至少给 retryKey 设 24h TTL；两份消费者公共逻辑抽到 common。
- 核实（TASK-128，2026-09-23）：两侧代码与原文引用行号逐一吻合零改动——verify 侧 :52 MAX_RETRY=3、:140 RECONSUME_LATER、:225-233 AtomicLong incrementAndGet 无 TTL、:236-245 sendToDlq；leaderboard 侧 :55/:126/:216-224/:227-236 同构。全仓 getAtomicLong 无任何 expire 调用。

### F10 JWT/内部接口密钥硬编码兜底（安全）
- 位置：user-service JwtUtil.java:48；gateway application.yml:110；common InternalApiAuthFilter.java:41；api InternalApiFeignInterceptor.java:19
- 改法：生产 profile 下无默认值、缺失即启动失败（仅 local profile 保留演示默认）；InternalApiAuthFilter 改常量时间比较（MessageDigest.isEqual）。

## P2 建议

### F11 登录锁定可被滥用做账号 DoS：auth:lock:{phone} 按手机号锁 15min，攻击者对任意手机号试错 5 次即锁定机主。改法：锁定叠加 IP 维度或改滑窗限流。（AuthService.java:264-288）
### F12 无登出/吊销：access token 签发后至过期前不可吊销。改法：access jti 黑名单（TTL=剩余时效）或文档声明接受 15min 窗口。
### F13 判定结果缓存多实例不一致窗口：Caffeine 单层 1min 无跨实例失效，多实例并发重判可能重复发事件（消费端幂等兜底，可接受；建议文档化或接入二级缓存失效广播）。（VerifyService.java:116）
### F14 规则链不短路：R1 已 HARD 命中仍执行 R5 远程调用。规范要求「收集全部命中」时维持现状；若只要判定结果可短路省一次 Feign。（VerifyEngine.java:78-83）
### F15 点赞对账全表扫 + 逐记录查询：reconcileLikeCounts 对每 record_id 一次 SELECT。改法：一条 GROUP BY 聚合 SQL 出全部计数，成员集只重建有漂移的。（RecordLikeService.java:263-291）【仍在（2026-09-23 核实 TASK-130）】
  → 核实：行号吻合（方法体 :264-291，:263 为 @Scheduled）；`:272 selectDistinctRecordIds()` + `:274` 循环内
  `:275 selectUserIdsByRecordId(recordId)` 逐记录一次 = N+1。**标题「全表扫」不准确**：`record_like` 主键
  `(record_id,user_id)`（sql/02-record-db.sql:64），`SELECT DISTINCT record_id`（RecordLikeMapper.java:68-69）
  走主键索引扫描，COUNT/user_id 查询是主键前缀查找——真正成本是**往返次数** O(N) SQL RTT + O(2N) Redis RTT
  （:280-284 每记录 DEL+SADD），周期 10min（:86）。**未被 TASK-108 系列顺带解决**（该服务全史仅 4 笔提交，
  无一条命中此处）；TASK-103 handoff 声称已聚合（selectCountsByRecord）与事实不符，`selectDistinctRecordIds`
  仍在原地。离线判别式现成（RecordLikeServiceTest mock RecordLikeMapper）。
### F16 点赞 flush 吞吐上限 200/5s：高峰积压。改法：批大小可配 + 队列长度指标告警。（RecordLikeService.java:80-82）【仍在（2026-09-23 核实 TASK-130）】
  → 核实：行号吻合（:80 FLUSH_BATCH=200 硬编码、:82 5_000ms、:213 range(0,BATCH-1)）→ 吞吐天花板
  **40 ops/s**；队列 `like:pending:ops` 无上限、无 Micrometer 指标、无背压。建议与 F15 并入同一变更
  （同文件同关注点）以减少轮红绿开销。TASK-103 handoff 声称已改 `@Value("${record.like.flush-batch:200}")`
  与事实不符，当前仍是 private static final 常量。
### F17 readCount 回源无防击穿：缓存 miss 并发回源 DB。改法：单 flight（Caffeine 或 SETNX 短锁）。（RecordLikeService.java:329-339）【仍在（2026-09-23 核实 TASK-130）】
  → 核实：行号**逐字吻合**（:330 GET → miss → :334 countByRecordId + :336 SET，无 SETNX/单飞/TTL）。
  两候选口径需用户拍板：① Caffeine 单飞（进程内，与 F13「Caffeine 单层多实例不一致」的既有结论口径需对齐）；
  ② SETNX 短锁 `lock:like:count-init:{recordId}`（跨实例一致，代价是自旋）。建议判别式走确定性形态
  （断言 setIfAbsent 被调用），不用 N 线程 latch 的 flaky 形态。TASK-103 handoff 声称已加 SETNX 与事实不符。
### F18 VerifyDegradeService 扫描缺索引：WHERE status=? AND created_at<? 无复合索引，全表扫。改法：sport_record 加 idx_status_created(status, created_at)。（VerifyDegradeService.java:106-110；sql/02-record-db.sql:27）【仍在（2026-09-23 核实 TASK-130）】
  → 核实：`sql/02-record-db.sql:13-30` 的 sport_record 仅有 `:27 idx_user_time`，**无 idx_status_created**
  （全 `sql/` + `scripts/db/` grep 0 命中）。TASK-103 handoff 声称已加 DDL 与迁移幂等段，与事实不符。
  **注**：F18 不在本任务侦察清单内，属 TASK-130 顺带核实（因与 F05/F06/F15~F17 同源于 TASK-103 台账虚报）。
### F19 Sentinel 兜底路由不全：ROUTE_IDS 只有 record/user/verify，漏 auth/leaderboard/mapmatch/admin 四个路由的代码兜底。（SentinelGatewayRuleConfig.java:46-48）
### F20 服务未容器化：compose 只编排中间件，6 个服务靠宿主机 java -jar；缺 Dockerfile、缺服务级编排与健康检查。改法：multi-stage Dockerfile + compose 服务层（可选 profile）。
### F21 CI 缺口：无静态检查（spotless/checkstyle）、无依赖漏洞扫描（OWASP dependency-check）、无镜像构建。改法：按需增补 CI 步骤。
### F22 VerifyService.verify 同一 recordId 并发重入无互斥：双判定重复回调依赖 3003 冲突重试收敛。改法：可配 Redisson 锁或文档化接受。（VerifyService.java:79-118）
  → **已裁定文档化接受（2026-09-23，TASK-129）**：后果链四环节逐条核实——① 双判定落库为 record_id 主键幂等写入（INSERT IGNORE 占位 + upsert 只覆盖不新增，VerificationResultMapper.java:17-28）；② record 回调状态已等目标态幂等跳过、乐观锁冲突返回 3003（SportRecordService.java:171/175-179，ResultCode.java:44），消费端删去重键重投、重入 verify 读终判走补偿回调收敛（VerifyEventConsumer.java:190-197、VerifyService.java:131-142）；③ 榜单侧 per-record 互斥锁 + 锚点行 INSERT IGNORE/乐观 UPDATE，双 VERIFIED 事件只加分一次（LeaderboardService.java:128-154）。**无双份加分可复现路径**，属冲突拒绝式收敛 + 消费幂等兜住。权衡说明已补入 VerifyService.verify javadoc（对齐 ADR-0009 表述风格）。**重开条件**：锚点行幂等或回调收敛链路被移除/实证失效，或出现可复现错态（重复加分/扣分），再立项可配 Redisson 锁。

### F23 治理面角色校验只有网关一个落点，服务侧零角色概念（安全/治理面）【新登记（2026-09-23，TASK-130 发现）】
- 现状：`common/.../internal/InternalApiAuthFilter.java` 只护 `/internal/**`（:68 判据、:89-95 实现），
  5 个后端服务均经 `scanBasePackages="com.sportverify"` 加载（各 Application.java:14/19/19/21/19）；
  **治理面端点不在 `/internal/**` 下**：`/admin/**`→verify `/api/appeals/**`（VerifyController.java:22）、
  `/verify/rules/**`→verify `/rules/**`（RuleVersionController.java:27，写端点 :34/:40/:47）；
  角色校验唯一落点 = `AuthGlobalFilter.java:102-105`（403/1002），角色来源 JWT claim。
- 证据：全仓服务代码 `X-Role` 读取点 **0**、`@PreAuthorize/@Secured/@RolesAllowed` **0 命中**；
  `RuleVersionController.java:22` javadoc 明写「鉴权只依赖网关过滤（本服务不自行校验 token）」。
- 直连面：compose 把 8081-8085 绑回环（docker-compose.services.yml:62/97/129/161/191），
  但全仓 `server.address` **0 命中** → 宿主机 `java -jar` 直跑默认绑 0.0.0.0（F20 记录的当前主路径），
  容器网络内可 `http://verify-service:8083/rules/**` 直连绕开治理面校验。
- 缺口性质：ADR-0007 §9 的「直连面凭证硬化」只覆盖 `/internal/**`，**治理面路径在直连面裸奔**，
  属 ADR 自身 hardening 意图的未覆盖区。
- 关键判断（供立项）：服务侧若靠**读 `X-Role` 头**判角色，安全增量**恒为零**（直连者同样能伪造该头）；
  加强方向应二选一——① 维持 ADR 边界，把凭证机制扩到治理面路径（网关注签、服务侧校验）；
  ② 服务侧加二道防线并绑定凭证。两方向论据与红绿判别式见 tasks/TASK-130/handoff.md 第 4 节。
- 处置：**需用户拍板**（方向选择属架构决策）。

## 维度总评
- 架构：模块边界清晰、契约/实现分离到位；主要欠账是「事件可靠性」（F03/F09）与「锁-事务顺序」（F04）。
- 并发：幂等/乐观锁/防重设计普遍扎实；个别规模性热点（F05/F06/F15）。
- 工程规范：注释与 ADR 文化优秀；CI、容器化、密钥治理是短板（F02/F10/F20/F21）。
- 安全：最大风险集中在默认配置（F01/F02/F08/F10），都是「本地演示默认」遗留。
- 治理面：越权防线按 ADR-0007 收敛在网关单点；直连面只硬化了 `/internal/**`（F23 登记），
  治理面路径（`/admin/**`、`/verify/rules/**`、榜单每日报表）在直连面零校验，方向待拍板。
- 台账可信度：**TASK-102 与 TASK-103 两份 handoff 均声称完成而代码零落地**（TASK-128 / TASK-130 核实），
  引用旧台账结论前须先按本清单的「核实」批注重新取证；F04 属主 agent 已裁定不修，不在此列。
