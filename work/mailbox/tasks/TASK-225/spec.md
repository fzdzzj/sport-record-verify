# TASK-225: Weekly/Monthly Sport Goals (P5, MEDIUM)

派发基线：TASK-224 已封盘（origin=9ed803f），封盘笔 77230c0 在本地随本课题批次推送（勿动勿 amend）。开工时工作树干净；offline 基线 52/50/173/226/159/80/26；token 基线 SUM=2036。提案三件套=spec/changes/add-sport-goal/。契约门在途基线笔=开工 HEAD。

## S0 hard constraints

- 红线零触碰：六服务白名单外 src/main、**api 模块**、pom、ci.yml、scripts、compose、web/package.json、vite.config.ts；**sql 仅允许 02-record-db.sql 幂等追加**（V21-V23 冻结区零触碰；01/03/04/05 零 diff）；无新路由 → typed-router 零 diff。
- 既有语义零改动：/my、/my/streak、feed、删除、筛选、visibility 门零弱化；goal 读不引入跨服务调用。
- 真实凭证零入库；命令行禁中文；MSYS sed -i 禁对 tracked 文件；D7 家法（TableInfoHelper + 新测试类隔离单跑）。
- **门禁档位：sql 触碰 → 按分级政策保守读法走 offline 全量逐位**（不得收窄）。

## S1 verified context（指导侧已知，执行侧核对）

- /my/streak 在 RecordController（record-service 拥有用户中心聚合面，goal 同域无需跨服务）。
- sql/ 为 01-05 基线文件制；TASK-221 先例 = 02-record-db.sql 建表内追加列 + 幂等模式 + scratch 二跑证明（执行侧核对 02 的幂等写法——CREATE TABLE IF NOT EXISTS 或 information_schema 分支——goal 表沿用同款）。
- 统计面板（TASK-211）在 web 首页/统计区，goal 卡片落位由 recon 定。

## S2 preregistered design

- 表 sport_goal：id PK / user_id BIGINT NOT NULL / period_type TINYINT NOT NULL（0=周 1=月）/ metric TINYINT NOT NULL（0=距离 km 1=次数）/ target_value DECIMAL(10,2) NOT NULL / created_at；UNIQUE(user_id, period_type, metric)——每维度单活跃目标，POST=upsert。
- 端点（GoalController，前缀 /api/records/my/goals）：GET 列表+进度；POST upsert（period/metric/target 校验：域外或 target<=0 → 400）；DELETE /{id}（owner 校验 403 code 1002，不存在 404 code 1001 同 requireRecord 语义）。identity 门与 /my 同构（缺头 1001；auth 关缺 userId 400）。
- 进度 pull-on-read：当前周期窗口（ISO 周 周一至周日 / 自然月），对本人 **PASSED** 记录 SUM(distance) 或 COUNT；返回 achieved/target/percent（0-100 封顶显示）；零反范式零事件零缓存。
- web：goal 卡片（每目标 a-progress + 周期标签 + 达成态）+ 新建/编辑对话框 + 删除确认；client.ts 三方法 + 测试。
- Non-Goals 见 proposal（历史目标归档/提醒/分享/自定义周期/多并发目标零涉及）。

## S2.5 discriminants

- R1 schema：02 追加恰 sport_goal 一段幂等建表；scratch 二跑命中跳过分支；UNIQUE 约束在。
- R2 identity/ownership：GET/POST 缺头 1001、auth 关缺参 400；DELETE 非 owner 403 1002、不存在 404。
- R3 upsert：同 (user,period,metric) 二次 POST 更新 target 不新增行；域外/越界 → 400。
- R4 进度：周窗口（周一 00:00 起）与月窗口（1 日 00:00 起）边界单测；distance 聚合与 count 聚合各自正确；percent 封顶 100；仅 PASSED 计入（PENDING/REJECTED/已删不计——删除后自然消失，与 TASK-223 联动语义留证）。
- R5 web：卡片渲染/进度条/对话框/删除确认；四件套承载。
- R6 既有面零改动：/my/streak/feed/删除/筛选源码零 diff。
- G1 offline 全量 record 只增、其余逐位不变；G2 白名单全等；G3 门禁全套含 CI。

## S3 stop conditions

1. 02 幂等模式与 S1 记载不符（或 TASK-221 先例实况冲突）。
2. offline 任何模块下降或 scratch 二跑非幂等。
3. 正确实现需触 api 模块/发 MQ/跨服务写。
4. 进度窗口与既有 streak/统计周期语义冲突需改基线行为。
5. 性能红旗（进度聚合全表扫需索引而索引超范围）。

## S4 whitelist（recon 定稿锁死，预估 12-16）

sql/02-record-db.sql；record-service：Goal entity 新、GoalMapper 新、GoalService 新、GoalController 新、DTO 新 1-2、GoalServiceTest 新、GoalControllerTest 新；web：index.page.vue（或统计组件）、client.ts、client.test.ts；README 1。台账 4 另计（C-02）。handoff §2 逐字符 git 全路径（D6 家法）。

## S6 gates (FULL)

offline 全量逐位（sql 触碰不收窄）+ scratch 迁移二跑证明 + 新测试类隔离单跑 + --static=record-service + web 四件套（TMP 重定向 .trae/tmp；typed-router 零 diff）+ 契约双态 + token 2036 持平 + 词面门 ZERO_HIT/探针正控 + diff --check + 行尾。独立复核四块（用户派发）；指导亲核后推送，CI 绿为收口前提。

## S7 backfill（C-02 后）

判别式红绿、门禁留证（含 scratch 二跑）、偏差 D1-Dn、未覆盖清单沿先例（真实浏览器 goal 交互/真实 DB 聚合联调/--it/online）。
