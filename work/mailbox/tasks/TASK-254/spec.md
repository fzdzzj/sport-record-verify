# TASK-254 任务书（spec）

> **TASK**：E 系批4a-1——网关与高危簇（E10 网关 HTTP 客户端超时 + E11 规则快照校验与引擎隔离 + E12 likes/batch 入参上限 + E16 Sentinel 空集护栏 + 两顺手项，FULL 档）
> **开工基线**：&lt;C-02 回填实际哈希&gt;（main，立项笔后工作树干净）
> **方案**：2026-10-16 E 系批4a-1 指导侧立项侦察，用户拍板「按建议先启动批 4a-1」立项
> **档位**：FULL（E11 触碰 verify 判定核心链路生产语义、E16 触碰网关限流安全语义——禁降级；多模块 gateway/verify/record + scripts/docs → 离线全量逐位门禁 + 独立复核五块）
> **状态**：已立项待实施

## 1. 背景与侦察结论（2026-10-16 指导侧亲验，四项坐实 + 一项反证出局）

- **E10 坐实**：gateway `application.yml` 全文无 `spring.cloud.gateway.httpclient.*` 超时段（对照 verify-service Feign default connect-timeout 1000 / read-timeout 3000、leaderboard 同款显式配置）——SCG 默认 response-timeout 无截止，上游 hang 死连接无限占用，Sentinel 限流对已建立连接零作用。
- **E11 坐实（两半）**：① `RuleVersionController` L35 虽有 @Valid @RequestBody，但 `RuleVersionCreateRequest.rules`（VerifyProperties）零约束零级联——快照阈值零校验；`R1SpeedRule` L50 `sum / (window - 1)`：windowPoints=1 → 除零（sum>0 得 Infinity 恒命中全拒、sum=0 得 NaN 静默永不命中）、windowPoints=0 → L45 循环 i 从 -1 起 `windowAvg[-1]` 数组越界；② `VerifyEngine` L78-83 规则链循环零 try/catch——单规则 RuntimeException 上抛，误下发一条坏 ACTIVE 快照即全判定停摆（重试耗尽进 DLQ）。
- **E12 坐实**：`LikeController` L88-93 `@RequestBody List&lt;Long&gt;` 零上限；`RecordLikeService.getLikesBatch` L345-358 逐 id selectById + assertCanView 循环（非本人非公开可见性走 Feign）——N 条 id 放大 N 次 DB + 至多 N 次 Feign（对照钳制先例：SportRecordService L713/L762 服务层数学钳制）。
- **E16 坐实**：`SentinelGatewayRuleConfig.toGatewayFlowRules` L99-107——Nacos 推合法空 JSON 数组经 `new HashSet&lt;&gt;(空表)` 得空集覆盖 register2Property 属性，L74 兜底 5000 QPS 被静默清空；仅解析失败返 null 保持旧版，合法空数组不在保护内。
- **E27 反证关闭（本课题零 E27 改动）**：`GatewayProbeTest` 为真实网关全上下文测试（@SpringBootTest RANDOM_PORT + TestRestTemplate 真 HTTP），断言 /actuator/health/liveness 与 /actuator/health/readiness 200/UP 且属基线 71 测全绿（CI 绿）——无 /actuator/** 路由时请求落入 actuator 端点映射而非 404，「经网关探针恒 404」前提不成立；白名单 /actuator/health* 为有效配置，compose 裸 TCP 检查仅风格项。BACKLOG 行登记反证关闭。
- **顺手项1**：`RecordLikeMapper` L64/L73 Javadoc 称「对账已改走 selectRecordLikePairs() 单次批量扫描」，与 L18 头注释现行口径（键集分页游标 selectDistinctRecordIdsAfter + selectPairsByRecordIds，selectRecordLikePairs 保留供 IT 直读）矛盾——注释滞后订正。
- **顺手项2**：`scripts/perf/healthcheck.sh` L9-10 经网关 8080 打 /user/internal/health、/verify/internal/health——TASK-251 后网关对 internal 路径段一律 404，两行冒烟恒 404；`docs/项目速览手册.md` 存在同款旧口径（grep internal/health 定位，README 已订正勿动）。

## 2. 改动文件白名单（15 项，双向全等硬约束）

业务/测试 13 项：

1. gateway-service/src/main/resources/application.yml（E10）
2. gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java（E16）
3. gateway-service/src/test/java/com/sportverify/gateway/config/SentinelGatewayRuleConfigTest.java（新建，E16）
4. gateway-service/src/test/java/com/sportverify/gateway/GatewayProbeTest.java（E10 资源断言扩展）
5. verify-service/src/main/java/com/sportverify/verify/service/RuleVersionService.java（E11 前半）
6. verify-service/src/main/java/com/sportverify/verify/algorithm/VerifyEngine.java（E11 后半）
7. verify-service/src/test/java/com/sportverify/verify/algorithm/VerifyEngineTest.java（E11 后半扩展）
8. verify-service/src/test/java/com/sportverify/verify/service/RuleVersionServiceTest.java（E11 前半扩展）
9. record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java（E12）
10. record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java（E12 扩展）
11. record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java（顺手1，仅 Javadoc）
12. scripts/perf/healthcheck.sh（顺手2a）
13. docs/项目速览手册.md（顺手2b）

台账 2 项（C-02 阶段）：work/mailbox/tasks/TASK-254/spec.md、work/mailbox/tasks/TASK-254/handoff.md。

零触碰声明（白名单外全仓零 diff）：LikeController.java（E12 校验落 service 层）、VerifyProperties.java / RuleVersionCreateRequest.java / RuleVersionController.java（E11 校验程序化落 service，不引注解级联避免 Nacos 配置绑定语义外溢）、AuthGlobalFilter 及网关鉴权面全部、除 gateway 外各服务 application.yml/properties、api 模块、全部 pom.xml、web/、scripts/verify/*、scripts/db/*、sql/*、.github/、PLAN.md、HANDOVER.md（子 agent 禁改三看板）。

## 3. 实现要求（判别式）

### R1（E10）网关 HTTP 客户端超时

gateway `application.yml` 的 `spring.cloud.gateway` 段内（与 routes/globalcors 同级）新增 httpclient 超时配置：connect-timeout 3000（毫秒）、response-timeout 10s。数值依据：内网 Feign 对照 connect 1000 / read 3000，网关为最外层代理取 connect 3s + response 10s 余量。纯配置零 Java 改动。`GatewayProbeTest` 追加资源断言用例：读 application.yml 断言两键存在（沿 readinessGroupStructureMatchesRealDependencies 资源断言范式）。

### R2（E11 前半）规则快照程序化校验

`RuleVersionService` 创建版本入口：request.getRules() 非 null 时逐项校验，违规抛 BizException（参数错误类，message 含字段路径与实际值）：

- rules.vDrift &gt; 0
- bySportType 每条目与 legacy r1-r4 同一套：r1.speed &gt; 0、r1.windowPoints &gt;= 2、r1.minWindows &gt;= 1、r2.accel &gt; 0、r2.minCount &gt;= 1、r3.minMinutes &gt;= 1、r3.maxMeters &gt; 0、r3.segmentRatio ∈ (0,1]、r4.maxRatio &gt;= 1、r4.minCumulativeMeters &gt;= 0
- r5：offRoadRatio ∈ (0,1)、hardOffRoadRatio ∈ (0,1] 且 &gt;= offRoadRatio、minPoints &gt;= 1

rules 为 null（快照当前基线）行为不变。VerifyProperties / RuleVersionCreateRequest / RuleVersionController 零改动。

### R3（E11 后半）VerifyEngine 单规则异常隔离

规则链循环体 per-rule try/catch：单规则 evaluate 抛任意 RuntimeException → log.error（含规则类名与异常摘要）+ 跳过该规则；其余规则照常评估、聚合评分不受影响、verify 正常返回。预处理段与聚合段不在隔离范围。

### R4（E12）likes/batch 入参上限

`RecordLikeService.getLikesBatch` 入口：原始入参（去重与过滤 null 之前）size &gt; 50 → 抛 BizException 参数错误（拒绝式非截断——防静默丢数据破坏「返回顺序与去重后可见记录顺序一致」契约）。50 为 Feed 单页记录数量级推定值，数值调整需指导侧授权。合法 ≤50（含重复与 null）行为不变。

### R5（E16）Sentinel 空集护栏

`toGatewayFlowRules`：解析成功但列表为空 → log.warn（注明「空规则集视为无效更新，保持上一版」）+ return null——与解析失败同一「保持上一版」语义。新增 `SentinelGatewayRuleConfigTest`（converter 纯逻辑三用例）：合法空数组 → null、非 JSON 垃圾 → null（既有行为回归）、合法两条规则 → size==2。允许为可测性将私有方法可见性调整为包私有或经反射调用（二择一，C-02 登记实际形态）。

### R6（顺手项）注释与脚本口径订正

- `RecordLikeMapper` L64/L73 两处 Javadoc 订正为与 L18 头注释统一的现行口径：对账已改走键集分页游标（selectDistinctRecordIdsAfter + selectPairsByRecordIds），本方法保留供 IT 直读，对账主路径对其调用须保持 0 次。
- `healthcheck.sh` L9-10 internal 冒烟两行改为现行可达口径（直连服务端口 /actuator/health 或经网关公开只读路由），保持脚本自检用途不变。
- `docs/项目速览手册.md` grep internal/health 定位旧口径行同步订正（README 已订正勿动）。

## 4. 测试与门禁要求

新增/扩展单测判别式（全部纯 JVM，零 Testcontainers/零新依赖）：

- E11 前半（RuleVersionServiceTest 扩展）：windowPoints=0 拒、windowPoints=1 拒、segmentRatio 越界拒、hardOffRoadRatio &lt; offRoadRatio 拒、全合法通过、rules=null 行为不变；
- E11 后半（VerifyEngineTest 扩展）：stub 一条 evaluate 抛 RuntimeException 规则 + 一条正常规则，断言 verify 正常返回、正常规则命中被收集、异常规则被跳过；
- E12（RecordLikeServiceTest 扩展）：51 条原始入参拒（断言异常与提示）、50 条含重复与 null 正常；
- E16（SentinelGatewayRuleConfigTest 新建）：三用例见 R5；
- E10（GatewayProbeTest 扩展）：资源断言见 R1。

七门禁全绿（唯一入口 scripts/verify/mvn-verify.sh；契约门 --open=TASK-254 --baseline=&lt;开工基线&gt;；token 门 29 项只增不减；词面门模式文件法 + 探针阳性对照）；surefire 只增不减（开工基线 52/71/205/300/188/108/29）。

## 5. 预注册受限与授权

1. E10 超时数值（3000/10s）为静态推演（内网 Feign 对照 + 余量），真实 hang 上游场景未实测（可选受限）。
2. E16「null 保持上一版」沿既有解析失败分支同一框架语义，Nacos 真实推送链路未实测（本机无 Nacos；converter 纯逻辑单测覆盖）。
3. E11 R3 隔离为单规则 fail-open（坏规则被跳过削弱该规则防线），由 R2 前置校验拦截坏快照 + log.error 告警补偿，登记为已知权衡。
4. E12 上限 50 为推定值，实施侧不得自行改数值。
5. 速览手册订正行号以实施时 grep 实际定位为准（时点漂移可能）。

## 6. 纪律

- 双提交：C-01 实施（恰上述 13 项业务/测试文件）+ C-02 台账（spec/handoff 纯追加回填）；提交消息 type(scope): description（TASK-254）。
- 新文件纯 LF + 完整末尾换行；改文件保持既有行尾；git diff --check 净。
- 禁 stash / add -A / 改归档 / 动 .codex 与 .trae；临时件放 .trae/tmp/ 用后删。
- Bash 脚本仅经 D:\git\Git\bin\bash.exe 执行。
- 停止条件：任一判别式无法按本任务书落地、需触碰零触碰面、offline 基线回退、checkstyle 违规增加——立即停止修改并汇报，不得自行变通。
