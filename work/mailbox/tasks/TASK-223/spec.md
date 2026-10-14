# TASK-223: Record Deletion / Withdraw (P5)

派发基线：TASK-222 封盘后（本地领先 1 笔=封盘笔 5509813 随本课题批次推送，勿动）；开工时工作树干净；offline 基线 52/50/173/206/159/80/26；token 29 项基线 SUM=2036；契约门在途基线笔=开工 HEAD。提案三件套见 spec/changes/add-record-deletion/。

## S0 hard constraints

- 红线零触碰：六服务白名单外 src/main、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/（本课题零 schema 零迁移——不加 deleted 列、不改状态机 CHECK）、web/package.json、web/vite.config.ts；typed-router 生成物仅当新增路由时沿 7e4be36/9db644a 先例随笔提交。
- 既有 visibility 门与全部既有读取面零弱化：assertCanView/pagePoints/getVerifyResult/like 三端点/feed 语义零改动（删除后自然 404 属预期行为而非门变更）。
- 不新增状态值（status 保持校验态语义）、不加软删/tombstone、不发 MQ/补偿事件、不做跨服务写路径。
- 真实凭证零入库（token SUM 持平）；命令行禁中文；MSYS sed -i 禁对 tracked 文件。
- 测试隔离纪律（D7 家法）：解析 MP wrapper SQL 的纯 Mockito 测试必须 TableInfoHelper.initTableInfo 惯用法；新测试类 -Dtest= 隔离单跑通过。

## S1 verified context (guidance-side recon, 2026-10-14)

- SportRecord：id/requestId/userId/sportType/startTime/endTime/distance/duration/status(Integer)/visibility/archived/version(乐观锁)/createdAt；轨迹双表 track_point + track_point_archive（archived 分支，pagePoints 已按 archived 切换）。
- 点赞：Redis-first（SADD/SREM 成员集 + INCR/DECR 计数 + 异步批量落库 + Redisson 锁 flush + 读兜底 COUNT 回填 + 对账重建）——删除后 DB like 行随事务清除，Redis 键 best-effort 清理、对账路径天然容忍悬挂。
- 榜单：leaderboard-service 自有持久层（@MapperScan 榜单域）+ UserApi 好友/昵称——**数据源归属（实时查 record-service vs 自有聚合表）为执行侧 recon 第一裁决点**，决定贡献消失是自动传播还是登记偏差。
- 通知：点赞通知为历史事实（沿 188 口径），不做追溯清理。

## S2 preregistered design

- 端点：DELETE /api/records/{id}（RecordController）。identity 门与 /my 同构（resolveUserId；缺头 1001）；归属校验 record.userId==caller 否则 403 code 1002（与 verify-result 越权同型）；不存在 → 404 code 1001 同 requireRecord 语义。
- 级联（record-service 单事务）：delete sport_record（by id+userId 条件删除，乐观锁不强制）+ delete track_point + delete track_point_archive + delete like DB 行（如按 record_id 维表）。事务外 best-effort 清 Redis 成员集/计数键（失败不回滚、对账收敛），留证失败容忍语义。
- 删除后读取面：points/verify-result/like 三端点/feed/my 对已删 id → 404（requireRecord 查不到，非门变更）；点赞对账 flush 遇已删记录 → 跳过或清键（recon 确认既有行为，不改语义则不动）。
- 榜单传播：S1 recon 若实时查询 → 自动消失（留证）；若自有聚合 → 登记 Dn 偏差 + 已知限制「贡献不实时回撤，刷新窗口沿榜单既有口径」，不做补偿写。若发现必须跨服务写才正确 → S3 §4 停止。
- web：我的记录列表行内删除按钮 + 二次确认弹窗；feed 卡片不放删除（非 owner）。client.ts delRecord + 测试。

## S2.5 discriminants

- R1 归属门：owner 删成 200；非 owner 403 code 1002；缺头 1001；不存在 404。
- R2 级联恰四表：record/track_point/track_point_archive/like 行同事务清（单测验证删除调用集与事务注解）。
- R3 Redis best-effort：键清理失败不阻断删除（异常吞并留证）；对账路径容忍悬挂（复用既有重建）。
- R4 读取面 404：删除后 points/verify-result/like/feed/my 均不可见（既有 requireRecord 语义承载，零门改动留证）。
- R5 榜单传播：按 recon 结论留证（自动消失或偏差登记），不引入跨服务写。
- R6 web：列表删除按钮+确认+删后列表刷新；type-check/build/test 承载。
- G1 offline record 净增、其余五模块逐位不变；G2 只改清单全等；G3 FULL 门禁含 CI 外部终验。

## S3 stop conditions（任一触发即停上报）

1. S1 recon 与实况不符（榜单数据源/点赞键形态/归档双表结构与 S1 记载冲突）。
2. offline 任何模块下降。
3. 删除语义与既有乐观锁/归档分支/对账机制冲突需改既有行为。
4. 正确性要求跨服务补偿写路径（超出预注册范围）。
5. 性能红旗：大轨迹删除超时/锁范围异常。

## S4 whitelist（recon 后定稿锁死，预估 8-12）

record-service（RecordController、SportRecordService、RecordLikeService 若涉对账交互、测试 2-3）/ web（index 页或列表组件、client.ts、测试 1-2）/ README 1。handoff §2 逐字符 git 全路径（D6 家法）。

## S6 gates (FULL tier)

同 TASK-222 全套：offline 全量逐位 + 新测试类隔离单跑 + --static=record-service + web 四件套（TMP 重定向 .trae/tmp）+ 契约双态 + token 2036 持平 + 词面门 ZERO_HIT/探针正控 + diff --check + 行尾。独立复核四块（用户派发）；指导亲核后推送，CI 绿为收口前提。

## S7 backfill (after C-02)

C-02 回填：判别式红绿、门禁留证、偏差 D1-Dn、未覆盖清单（真实浏览器删除交互/真实 Redis 联调/--it/online 沿先例口径）。

### 回填（2026-10-14，C-02 台账笔）

- **判别式红绿**：R1-R6 / G1-G3 逐条实证绿，证据见 `handoff.md §4`。
- **门禁留证**：offline 全量逐位（52/50/173/217/159/80/26，record +11）、测试隔离单跑、静态 record-service、web 四件套、契约门双态、token 29 项两点（2036 持平）、词面门 CI-exact ZERO_HIT + 探针正控、`git diff --check`、行尾，均留证于 `handoff.md §5`。
- **偏差**：D1（榜单数据源定稿为自有聚合持久表，贡献不实时回撤，登记为偏差与已知限制，不引入 MQ 补偿写）；D2（Checkstyle ParameterNumber 8 参家法下采用 setter 注入）；D3（token/词面门临时件落 gitignored `.trae/tmp`）；D4/D5（offline 噪音与 build chunk 告警为既有）。明细见 `handoff.md §8`。
- **未覆盖清单**：真实浏览器删除与二次确认交互、真实 MySQL 分片删除与 Redis 联调（未起全栈服务，`--it` 未执行）、`--mode=online` 与外部 CI。均沿 TASK-213/220/221/222 口径登记，未写成通过，见 `handoff.md §10`。
- **停止条件**：S3 §1-§5 无一按字面触发（对账见 `handoff.md §8`）。
- 指导侧封盘补记（2026-10-14）：run 38497654651（head=765b366）conclusion=success——外部终验一次达成，TASK-223 封盘；复核留痕×3 与 D1 榜单已知限制（挂账 F5 拟）随 PLAN.md 补记登记。
