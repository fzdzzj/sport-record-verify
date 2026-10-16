# 方向候选与待办（BACKLOG，指导侧常备）

> 用途：指导主agent立项侦察与交接的常备清单；每批次封盘后更新状态。执行/复核侧不直接修改本文件。
> 挂载：PLAN.md 末节「方向候选与待办」指向本文件；立项时从此清单取号（TASK-xxx），封盘后回写状态与课题号。

## 2026-10-13 批（TASK-221 收口期立项侦察产出）

| 编号 | 方向 | 依据 | 价值 | 预估档 | 状态 |
| --- | --- | --- | --- | --- | --- |
| F1 | 好友动态流 | TASK-221 可见性三态 + friendship 表 + 点赞/通知基建就绪，唯独缺「看好友在干什么」入口 | 可见性三态直接复利；社交链闭环最后一环（加好友→看动态→点赞→通知）；顺带真实链路锤炼跨服务好友判断 | FULL | **已封盘 TASK-222（2026-10-14，CI run 38431818947 attempt 2 绿）** |
| F2 | 记录删除/撤回 | 用户主链路只有提交+查看，数据自主权最后缺口（PRIVATE 之外） | 误传/隐私后悔药；级联面广（状态机/点赞/榜单贡献/通知）需预注册裁决 | FULL | **已封盘 TASK-223（2026-10-14，CI run 38497654651 绿）**；D1 榜单贡献不回撤挂账 F5（拟） |
| F3 | 周月运动目标 | 连续打卡（TASK-220）+ 统计面板（TASK-211）已有，缺目标驱动 | 目标+进度条粘性功能；读写面小 | MEDIUM | **已封盘 TASK-225（2026-10-14，CI run 38525589652 绿）** |
| F4 | 我的记录筛选 | 我的记录列表已有分页，无类型/时间/状态过滤 | 小体量体验补全 | MEDIUM 小 | **已封盘 TASK-224（2026-10-14，CI run 38511006257 绿，分级政策首用）** |
| H1 | E2E 真实浏览器验证批 | TASK-213/218/219/220/221 五课题连续登记「真实浏览器交互未跑」 | 前端体验债集中偿还；Playwright 选型需重议（TASK-215 曾裁定不引入） | MEDIUM | **已封盘 TASK-227（2026-10-14，CI run 38586443704 绿；首轮 S3.3 停止发现 P0 启动级路由缺陷→TASK-228 同批修复后续批达成）** |
| H2 | E5 生产部署姿态演练 | TASK-205 后置议题长期在册（CORS 真实注入/跨域 cookie/TLS 终止协同） | 上生产前必做 | MEDIUM 零代码 | 候选 |
| H4 | web 交互面修整批 | TASK-227 附带发现：vite proxy 前缀误伤 preview 直载（/record-submit 等）+ record-submit a-alert 插槽 antdv 4.2.6 不落屏 | 上生产前与 H2 一并处置 | MEDIUM 小 | **已封盘 TASK-229（2026-10-15，本地全绿待推）** |
| H3 | LIGHT 清理批 | TASK-221 留痕-1（测试文件行尾）+ README 旧表述 + 静态基线偏差登记 | 台账卫生（沿 TASK-212 先例） | LIGHT | **已封盘 TASK-226（2026-10-14，CI run 38586443704 绿）** |
| F5 | 榜单贡献回撤 | TASK-223 记录删除时自有聚合表与 ZSet 里程未回撤（D1 挂账） | 社交与榜单数据一致性闭环 | MEDIUM | **已封盘 TASK-230（2026-10-15，4bae3fe 绿）** |
| P2 | 个人统计全量聚合端点与文案清洗 | TASK-211 首页统计卡 500 条截断 + 翻页耦合技术债 + 早期脚手架文案残留 | 核心业务指标准确性与前端数据解耦 | MEDIUM | **已封盘 TASK-231（2026-10-15，ee95c49 / e4f0aa2 绿）** |
| H5 | Web E2E 路由 mock 同步与断言对齐 | TASK-231 复核 N-3：`web/e2e/helpers.ts` 缺 `/my/stats` mock 致 `record-list.e2e.ts` 三断言失效 | 保证 Playwright E2E 套件契约对齐（CI web 段暂未接入但本地可用） | LIGHT/MEDIUM 小 | **已封盘 TASK-233（2026-10-15，5e675e1 / d375b8e / 64459c8 绿）** |
| P3 | 个人统计 SQL 下推与加载态解耦 | TASK-231 复核 N-4/N-6：10 万级单用户内存聚合与 loadData 翻页重复触发优化 | 服务端大容量高吞吐与前端体验平滑 | MEDIUM | **已封盘 TASK-232（2026-10-15，ceec5c6 / bf4c7b9 绿）** |
| P4 | 原有功能健壮化 | 提交合理性推导 + 目标边界与整数性 + 榜单周期联调 + 公开标签补齐 | 系统核心链路数据完整性与交互可用性闭环 | MEDIUM | **已封盘 TASK-234（2026-10-15，169e367 / 54977f5 绿）** |

## 2026-10-15 批（全模块深度代码与业务常理审计产出）

| 编号 | 方向 | 依据 | 价值 | 预估档 | 状态 |
| --- | --- | --- | --- | --- | --- |
| B1 | 核心链路缺陷修复（榜单结算/申诉回写/打卡时间/社交待办） | markSettled 无条件刷当前时间摧毁周期榜 + 申诉改判未回写 verification_result + streak/stats 误用 createdAt 导致断签 + 好友申请手工输入 ID | 根除系统级核心业务失真与断环 | MEDIUM | **已封盘 TASK-235（2026-10-15，3d31065 / 42f6f2e 绿）** |
| B2 | 社交与点赞交互补齐 | 动态流与详情页缺点赞交互按钮与计数实时同步 | 核心社交与互动体验闭环 | MEDIUM | **已封盘 TASK-236（2026-10-15，b6699f2 / b41fe19 绿）** |
| B3 | 全链路闭环体验补齐 | 首页被拒记录申诉直达 + 详情页申诉引导 + 通知中心下钻与标已读 + 顶栏登录态感知与设置入口 | 核心功能端到端闭环体验与交互可用性提升 | MEDIUM | **已封盘 TASK-237（2026-10-15，1d37ed0 / 8177e97 绿）** |
| B4 | 运动轨迹全链路真实化与反作弊合理性 | 详情页长轨迹分页循环突破 500 点 + GPX 完整导出 + SVG 局部等距等比投影矫正 + R4 起征门槛防微短误杀 | 运动轨迹数据完整性与几何图形真实展示 | MEDIUM | **已封盘 TASK-238（2026-10-15，b693fe9 / 50f85f7 绿）** |
| B5 | 好友榜高并发查询优化 | topFriends 全量遍历 ZSet 性能灾难，改为基于好友 ID 的 Redis Pipeline 批量 ZSCORE 检索 | 高吞吐接口性能与服务稳定性 | MEDIUM | **已封盘 TASK-239（2026-10-15，3cf0406 / 7c6d9ef 绿）** |
| B6 | 好友关系表 user_high 索引补齐 | friendship 仅联合主键 (user_low, user_high)，listFriends 在 user_high 侧全表扫描 | 消除核心社交慢 SQL，触发 index_merge 优化 | LIGHT | **已封盘 TASK-240（2026-10-15，86be2d8 / f0ea149 绿）** |
| B7 | 动态流 Feed 点赞态批量下推 | feed.page.vue 单页 20 条记录并发发起 20 次 getLike HTTP，网络与网关开销大 | 20 次 HTTP 合并为 1 次，吞吐与首屏性能提升 | MEDIUM | **已封盘 TASK-240（2026-10-15，86be2d8 / f0ea149 绿）** |
| B8 | 申诉复核管理后台端到端闭环 | verdict 提交后仅展示 ID，admin-appeal 需盲打 ID；无自动回填与直达链接 | 消除断层体验，完成审核管理闭环 | LIGHT | **已封盘 TASK-241（2026-10-15，8f7601d / a6cd2c1 绿）** |
| B9 | 提交表单客户端幂等加固 | 前端仅靠局部 loading 锁，弱网重试未带固定 requestId，可能产生重复单号 | 端到端网络闪断重试幂等防重 | LIGHT | **已封盘 TASK-241（2026-10-15，8f7601d / a6cd2c1 绿）** |
| B10 | 周期榜与结算全表慢查消除 | `leaderboard_contribution` 仅有 (record_id) 和 (user_id)，周期榜聚合及定时结算 markSettled 全表扫描 | 消除核心慢 SQL，大幅减轻 DB 锁与 I/O | LIGHT | **已封盘 TASK-242（2026-10-15，8804c71 / cd0f5be 绿）** |
| B11 | Outbox 毒丸事件防死信隔离 | verify_event_outbox 超过 maxRetry 仍保持 PENDING，Relay 轮询引发严重 I/O 放大 | 终态隔离保障高频 Relay 吞吐 | LIGHT/MEDIUM | **已封盘 TASK-243（2026-10-15，b50829a / 3f6856c 绿）** |
| B12 | 规则缓存并发防击穿硬化 | 规则缓存重建 tryLock 超时后无锁直接穿透 dbLoader.get() | 引入轮询重试收敛，防规则发布并发打穿 DB | LIGHT | **已封盘 TASK-243（2026-10-15，b50829a / 3f6856c 绿）** |
| B13 | 好友申请生命周期闭环 | PENDING 状态永不过期，同向反向双向拦截造成永久社交死锁 | 消除业务逻辑死锁缺陷 | MEDIUM | **已封盘 TASK-243（2026-10-15，b50829a / 3f6856c 绿）** |
| B14 | 好友社交闭环体验补齐 | friends.page.vue 漏调 loadFriends()；全仓缺失解除/删除好友端点与逻辑 | 补全核心社交闭环最后一公里 | MEDIUM | **已封盘 TASK-242（2026-10-15，8804c71 / cd0f5be 绿）** |
| B15 | 申诉复核中心待办闭环 | 管理端无待办列表接口，只能盲打 ID 复核 | 治理后台闭环队列化管理 | MEDIUM | **已封盘 TASK-244（2026-10-15，e753b39 / 0c7b7a5 绿）** |
| B16 | 记录删除与榜单回撤对账补偿 | 删除记录回撤 Feign 失败仅 WARN 留痕，里程在榜单永久残留脏态 | 保证跨服务分布式数据最终一致性 | MEDIUM | **已封盘 TASK-244（2026-10-15，e753b39 / 0c7b7a5 绿）** |
| B17 | 通知中心排序索引与批量标记 | notification 分页跳过 is_read 引发 filesort；前端缺复选批量操作 | 消除慢排序，提升消息处理体验 | LIGHT/MEDIUM | **已封盘 TASK-244（2026-10-15，e753b39 / 0c7b7a5 绿）** |
| B18 | 总榜 Redis 内存防爆与倾倒重构 | leaderboard:overall 无容量上限；定时结算 dump range(0,-1) 存在 OOM 风险 | 架构级防爆，保护高并发 Redis 与 JVM | MEDIUM | **已封盘 TASK-244（2026-10-15，e753b39 / 0c7b7a5 绿）** |
| B19 | 目标次数整数化与近 30 天空态退化 | 目标次数带无意义 .00；30 天无数据渲染 1px 细线虚框 | 消除数值常理违和，补齐首屏优雅引导 | LIGHT | **已封盘 TASK-242（2026-10-15，8804c71 / cd0f5be 绿）** |
| B20 | Outbox 历史清理与毒丸人工重放 | verify_event_outbox 缺乏 SENT 清理；EXHAUSTED 死信缺乏管理看板与重放 API | 消除单调膨胀，建立消息自愈与排查看板 | MEDIUM | **已封盘 TASK-246（2026-10-16，761f4ed / be3853e 绿）** |
| B21 | 通知中心历史已读清理与归档 | notification 表无 created_at 索引且已读永不清理，易引发锁竞争 | 索引优化与 90 天已读定时淘汰 | LIGHT | **已封盘 TASK-246（2026-10-16，761f4ed / be3853e 绿）** |
| B22 | 运动记录可见性修改与详情补全 | 提交后无 PATCH visibility 端点；详情页仅有折线缺失里程/时长/配速等指标 | 补全记录详情卡并支持原地修改可见性 | MEDIUM | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| B23 | 好友榜多级缓存防护防穿透 | 好友榜缓存 0 命中，高并发下双 Feign + Pipeline 易打崩 user-service | 引入 10~15s 短期多级缓存阻断雪崩 | MEDIUM | **已封盘 TASK-246（2026-10-16，761f4ed / be3853e 绿）** |
| B24 | 网关未捕获异常统一标准包装 | 网关异常回退 Spring 默认 503 HTML/空 JSON，前端弹英文网络错误 | 网关异常统一转换为 Result.failure 契约 | LIGHT | **已封盘 TASK-246（2026-10-16，761f4ed / be3853e 绿）** |
| B25 | 用户资料修改与好友申请批量 | 申请列表只显数字 ID 且需单点；设置页无昵称维护入口 | 申请回填对方昵称，支持批量同意/全部忽略；个人中心支持改昵称 | MEDIUM | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| B26 | 申诉全链路进度条与外链修复 | 申诉提交后误导跳管理后台（403）；刷新后输入框重置易致 3005 冲突 | 移除错误外链，引入 steps 进度条透明追踪并防重复提交 | MEDIUM | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| B27 | 动态流视觉与富文本空态引导 | 卡片无头像，英文运动标签；空态大面积留白无转化指引 | 彩色首字头像 + 中文运动徽标，空态提供去加好友/去跑步引导 | LIGHT | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| B28 | 轨迹 GPX 文件前端导入解析 | 仅支持样例轨迹与导出，无外部 GPX 上传解析支持 | 纯前端 parseGpx，支持拖拽/选择 GPX 自动解析填入 | LIGHT | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| B29 | 轨迹可视化起终点特征高亮 | SVG 轨迹全点单一红圈，无法分辨行进起止方向 | 首点绿色起点（🟢）与末点红色终点（🔴）特征高亮 | LIGHT | **已封盘 TASK-245（2026-10-15，23f58e3 / 5a275ab 绿）** |
| H2 | E5 生产部署姿态演练 | TASK-205 后置议题长期在册（CORS 真实注入/跨域 cookie/TLS 终止协同） | 上生产前必做 | MEDIUM | **已封盘 TASK-247（2026-10-16，89ba3d1 / 47f1ecb 绿，CI run 38782194561 外部终验达成）** |

## 2026-10-16 批（D 系只读立项侦察产出，指导侧证据抽查 8/8 坐实）

| 编号 | 方向 | 依据 | 价值 | 预估档 | 状态 |
| --- | --- | --- | --- | --- | --- |
| D1 | 总榜二级缓存与入榜/回滚一致性 | `@Cacheable leaderboard:overall` 双层 TTL=5min（CacheConfig L49），`applyVerified`/`rollbackOnRejected` 改 ZSet 后从不 evict——删除/驳回回撤后总榜最长 5min 显示旧里程，新入榜最长 5min 不出现 | 高 | MEDIUM | **已封盘 TASK-249（2026-10-16，`f9405d8` / `91c10c2`，独立复核五块 PASS）** |
| D2 | appeal 待审队列 status 索引补齐 | `appeal` 表仅 id PK + uk_record，`listPendingAppeals` WHERE status=PENDING ORDER BY id 全表扫描（对比 outbox 表已有 idx_status_id） | 中 | LIGHT | **已封盘 TASK-249（2026-10-16，`f9405d8` / `91c10c2`，独立复核五块 PASS）** |
| D3 | 结算对账 scan 游标续用 | `settleAndReconcile` 清残留成员每页重开 `opsForZSet().scan()` 不续 Redis cursor；**立项侦察升级定级：成员 ≥500 时推进判据永不满足，为不可终止循环（潜伏 P1）** | 中→高 | LIGHT/MEDIUM | **已封盘 TASK-249（2026-10-16，`f9405d8` / `91c10c2`，独立复核五块 PASS；不可终止循环已修复）** |
| D4 | 改密全量吊销 KEYS 扫描替换 | `AuthService.java:418` `stringRedisTemplate.keys(auth:refresh:{uid}:*)` 全 keyspace 阻塞扫描，宜改 SCAN 渐进迭代 | 中 | LIGHT | **已封盘 TASK-250（2026-10-16，`32c1d8f` / `f1dd3e4`，独立复核五块 PASS；立项定级 FULL：auth 凭证路径生产代码）** |
| D5 | WS STOMP 端点 Origin 纵深 | `WebSocketNotificationConfig.java:46` `addEndpoint` 未 setAllowedOrigins；CONNECT 帧鉴权兜底非数据泄露，属纵深加固 + 生产姿态验证项 | 中 | LIGHT | **已封盘 TASK-250（2026-10-16，`32c1d8f` / `f1dd3e4`，独立复核五块 PASS；立项定级 FULL：WS 访问控制安全语义）** |
| D6 | mapmatch 候选集数量上限防护 | `MapMatchService.java:88-99,135-148` 跨大片区域 bbox 过宽时 ST_DWithin 候选边可能巨量全拉 JVM 逐点垂距，无上限/耗时防护 | 低-中 | MEDIUM 小 | **已封盘 TASK-249（2026-10-16，`f9405d8` / `91c10c2`，独立复核五块 PASS）** |
| D7 | 点赞对账全表拉取分批化 | `RecordLikeService.java:514` `selectRecordLikePairs()` 每 10min 整表 (record_id,user_id) 拉进 JVM 内存 | 低 | LIGHT | **已封盘 TASK-250（2026-10-16，`32c1d8f` / `f1dd3e4`，独立复核五块 PASS；并入 FULL 档多模块批，键集分页 500/批）** |
| D8 | R5 降级窗口可观测告警 | `R5OffRoadRule.java:67-77` mapmatch 熔断期降级「不命中」为有意 tradeoff 非缺陷；建议补「R5 降级期间通过率突变」监控告警防误判 | 低（提示） | LIGHT | **已封盘 TASK-250（2026-10-16，`32c1d8f` / `f1dd3e4`，独立复核五块 PASS；连续 5 次降级恰一次 error 告警）** |

> 侦察排除声明（防重复立项）：Outbox 毒丸隔离、规则灰度一致性、verify 判定幂等 + record 状态机乐观锁、好友并发互加/解除、点赞幂等、网关鉴权/CORS/治理凭证、track_point 分片索引、前端契约/WS 队列名均已逐文件核审无问题；D 系静态推论项（D2/D3/D6/D7）真实影响需立项后 EXPLAIN/压测确认。

推荐序：
1. **D 系 8 项候选全部收口完毕**：D1/D2/D3/D6 封盘于 TASK-249（2026-10-16，CI run `38839626456` 全绿），D4/D5/D7/D8 封盘于 TASK-250（2026-10-16，`32c1d8f` / `f1dd3e4`，独立复核五块 PASS，FULL 档终裁 ACCEPT，CI run `38849720261` 全绿）；
2. **下一批次**：E 系全仓只读立项侦察已完成并归档（见下节，2026-10-16），待用户拍板批次组合。

## 2026-10-16 批（E 系全仓只读立项侦察产出，31 项候选；指导侧抽查 E1/E2/E3/E15 亲验坐实、E4 与本机 CI run `38849720261` annotations 互证，◎ 标注项为侦察代理证据未经二次复核）

| 编号 | 方向 | 依据 | 价值 | 预估档 | 状态 |
| --- | --- | --- | --- | --- | --- |
| E1 | 内部接口凭证经网关可达 → 自助提权 | 网关仅清治理头与 X-User-Id/X-Role、从不清 `X-Internal-Token`（AuthGlobalFilter L129-132/229-238）；route-user `StripPrefix=1` 使 `/user/internal/**` 落上游 internal 面（六处「网关无 /internal 路由」书面前提被证伪）；密钥默认字面量 + strict=false 不拒启（InternalApiAuthFilter L43-50）；`/internal/auth/grant-admin` 外部自授 ADMIN，README curl 自证走网关 8080 | 高 | MEDIUM | **已封盘 TASK-251（2026-10-16，C-01 `124c93e` / C-02 `fd14459`，独立复核五块 PASS 零阻断，FULL 档终裁 ACCEPT）：网关全分支剥离 X-Internal-Token + internal 路径段 404 拒绝 + compose 密钥必填插值** |
| E2 | 自助改密经网关恒 401 主链路不可用 | `/api/auth/**` 白名单分支 stripIdentityHeaders 删 X-User-Id（AuthGlobalFilter L149-151），改密端点强制取该头缺失即 401（AuthController L150-168）→ authEnabled 真假两条路径均失败；测试面自行 addHeader 从不过网关掩盖 | 高 | FULL | **已封盘 TASK-251（2026-10-16）：whitelist 收窄为 register/login/refresh/logout 四端点，change-password 走鉴权分支网关注入 X-User-Id，前端零改动核实成立** |
| E3 | 通知清理端点零鉴权跨用户物理删除 | `DELETE /api/notifications/expired` 全控制器唯一不 resolveUserId 不判角色（NotificationController L173-177），mapper 全表 DELETE 无 user_id（L42），治理面清单不含该路径 → 任意 token 可清空全平台已读通知 | 高 | FULL | **已封盘 TASK-251（2026-10-16）：网关 admin.paths 精确串 + 服务层 requireAdmin 失败关闭双层防线** |
| E4 | CI runner 迁移预备 ★时间窗硬约束 | 5 个 action 全 node20 代 @v4 被强制跑 Node 24（checkout/setup-java/cache/setup-node ≥v5、upload-artifact ≥v6），ubuntu-latest 2026-10-19 起→Ubuntu 26（逃生开关已下线）；无 workflow_dispatch（无法 dry-run）/concurrency/timeout-minutes；Docker 链三处（compose config/6 镜像构建/Trivy）为迁移红风险面 | 高 | MEDIUM | **已封盘 TASK-252（2026-10-16，C-01 `66d0ec3` / C-02 `49b02c7`，MEDIUM 档指导亲核三块 PASS）：build/web 钉 ubuntu-24.04 + 六 uses 升 v5/v6 + workflow_dispatch/probe-ubuntu26 试跑通道（10-19 标签可用后手动 dispatch 验证）+ concurrency/timeout + trivyignore 校准** |
| E5 | record 4 个 @Scheduled 共用单线程调度器 | 全仓无 spring.task.scheduling.pool.size 配置走 Boot 默认单线程；archiveSweep 无 initialDelay 启动即占唯一线程（单记录点数无上限），可饿死 5s flush 与对账、compensateStuckVerifying 逐条 Feign 探活进一步阻塞 | 高 | LIGHT | **已封盘 TASK-253（2026-10-16，C-01 `1c35a27` / C-02 `22a0802`，独立复核五块 PASS 零阻断）：指导亲验坐实 4 @Scheduled（flush L419/reconcile L515/degrade L97/archive L81）；修法落地 `spring.task.scheduling.pool.size=4`（properties 条件项）+ 受限登记 flush∥reconcile 并发与多实例姿态同口径下轮自愈** |
| E6 | 申诉终判跨服务非原子 record 永久卡 APPEALING | 先提交 appeal→RE_PASSED+outbox（VerifyService L260）后 Feign 回调 record（L282-291），②③④任一失败即写偏斜；重试被 3006 拒绝、reconcileCallback 只补 VERIFYING/MANUAL_REVIEW 不含 APPEALING，无补偿路径 | 高 | MEDIUM | **已封盘 TASK-253（2026-10-16，同批）：指导亲验坐实 record-service 零 verify 事件消费者；修法落地 `AppealReconcileService` 兜底对账（@Scheduled 10min + Redisson 锁双跳过 + idx_status_id 批扫 100 + 恰 APPEALING 才乐观锁修复 + 单行 catch 续批），processAppeal 行为不变** |
| E7 | 结算 scan 清残留误删并发新入榜成员 | 先 SELECT target 后按「target 缺失」判 ZREM（LeaderboardService L474-513/568），SELECT 后并发 applyVerified 入榜成员必被删且 ZADD 不补回，总榜 evict 后掉榜最长 10min | 高 | MEDIUM | **已封盘 TASK-253（2026-10-16，同批）：并发复验坐实 applyVerified 先 DB 后 ZSet；修法落地 `flushStale` ZREM 前批候选 `selectActiveUserIds` 二次复核仍缺才删、复核命中保留、异常保守跳过（宁漏删不误删），窗口秒级→毫秒级残余下轮收敛** |
| E8 | 前端并发 401 各自刷新败者强制登出 | client.ts 每 401 各自 await refresh 无共享 in-flight Promise，后端 GETDEL 原子消费旧 jti 并发仅一赢家，败者 clearTokens 把赢家刚写入的 access 一并清 → 并发页必然整页踢回登录 | 高 | MEDIUM | **已封盘 TASK-257（2026-10-17：client.ts 模块级单例 refreshPromise 共享刷新 + 两并发 401 恰一次 refresh 用例；C-01 `033110a` / C-02 `5788184`，MEDIUM 档亲核三块 PASS，CI 读数由补记笔登记）** |
| E9 | 迁移漂移：archived 列无升级路径 + 归档分片缺索引 | sport_record.archived 仅 initdb 脚本出现（migrate.sh 升级路径缺列 → 存量库查询即 BadSqlGrammar 上线即故障）；add-idx-record-seq 覆盖 17 张表却无 track_point_archive | 高 | LIGHT | **已封盘 TASK-253（2026-10-16，同批）：指导亲验坐实 05 脚本 L236 注释自证非幂等 + migrate.sh 全量重跑无该列迁移；修法落地 `add-track-archive-column-and-idx.sql`（archived+idx_archive 与 05 脚本 L239-240 逐字一致、16 分片 idx_record_seq 逐段幂等、只加不删），05/migrate.sh 零触碰** |
| E10 | 网关 HTTP 客户端无连接/响应超时 | gateway yml 全文无 httpclient.connect-timeout/response-timeout（对照 leaderboard/verify 均显式配置），hang 住连接无截止，Sentinel 限流对已建立连接零作用 | 高 | LIGHT | **已封盘 TASK-254（2026-10-16）：gateway yml spring.cloud.gateway.httpclient connect-timeout 3000 / response-timeout 10s + GatewayProbeTest 资源断言** |
| E11 | 规则快照阈值零校验 × 规则链无异常隔离 | RuleVersionCreateRequest rules 无 @Valid 无 @Min；window=0 数组越界 / window=1 NaN 静默永不命中；VerifyEngine 无单规则 try/catch，误下发一条 ACTIVE 快照即全判定停摆进 DLQ | 高 | MEDIUM | **已封盘 TASK-254（2026-10-16）：RuleVersionService 程序化校验全套（vDrift/legacy/bySportType 同套/R5）+ VerifyEngine 规则链 per-rule try/catch 隔离** |
| E12 | likes/batch 无入参上限放大 N 次 DB+Feign | @RequestBody List 无 @Size，逐 id selectById + 逐条 areFriends Feign（对照 RecordController 同域有 size 钳制） | 高 | LIGHT | **已封盘 TASK-254（2026-10-16）：getLikesBatch 入口去重前 size>50 拒绝式 BizException（非截断），50 推定值未改动** |
| E13 | PostGIS 匹配 SQL 从未对真实库执行 | 内联 ST_DWithin/ST_MakeEnvelope SQL 仅 mock JdbcTemplate 桩测，全仓无 Testcontainers、mapmatch 无任何 IT，SRID/参数序/GIST/度换算偏差全无验证，直接决定 R5 判定正误 | 高 | FULL | **拆出另立课题（2026-10-16 批4b 切分：真实 PostGIS 库复验路径独立成课题拟 TASK-258，待批4b-1/4b-2 封盘后立项；内联 SQL 仅 mock 桩测现状坐实）** |
| E14 | E2E mock 兜底 404 掩盖通知写路径 | helpers 未映射请求兜底 404 code:9999 不进 unhandled 断言（假安全）；client.ts 清空已读/batch-read 两写端点无 mock → 通知页三按钮 E2E 恒失败无红灯 | 高 | MEDIUM | **已封盘 TASK-257（2026-10-17：ApiCtx unmocked 列表可见性 + notifications store 状态化 + 两写端点 mock + notifications.e2e 三按钮覆盖；C-01 `033110a` / C-02 `5788184`，MEDIUM 档亲核三块 PASS，CI 读数由补记笔登记）** |
| E15 | 登录锁定 threshold=5 使 F11 账号 DoS 回归【排除项新证据】 | user yml threshold: 5 enabled: true 覆盖代码默认 20（AuthService L84），spec-delta 明确该值语义为「5→20 兜底阈值」防分布式爆破 DoS，findings-summary 已记 F11 关闭；README 同步滞后；Nacos 运行时覆盖未核 | 中 | FULL | **已封盘 TASK-256（2026-10-16：yml threshold 5→20 + 注释/README 双维度口径 + LoginLockoutConfigTest 资源断言；C-01 `a89d095` / C-02 `2a2f11f`，指导亲核 + 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E16 | Sentinel 动态规则 [] 静默清空兜底限流 | 合法空数组走 new HashSet 得空集覆盖 loadRules 兜底（SentinelGatewayRuleConfig L74-107），日志仍报「已注册可动态改」；空集应视为无效更新 | 中 | MEDIUM | **已封盘 TASK-254（2026-10-16）：toGatewayFlowRules 空列表 → log.warn + return null 沿解析失败「保持上一版」语义 + SentinelGatewayRuleConfigTest 三用例（Nacos 真实链路登记受限）** |
| E17 | mapmatch 无 DB 超时 + TRUNCATE 重灌 + 采样上限无下界【合并】 | hikari 仅 initialization-fail-timeout、URL 无 socketTimeout、queryForList 无 queryTimeout；import 脚本 TRUNCATE 取 ACCESS EXCLUSIVE 锁阻塞读 → 重灌窗口 R5 全量误拒；max=1 除零 / max=0 NaN fail-open | 中 | MEDIUM | **已封盘 TASK-255（2026-10-16）：URL connectTimeout=3&socketTimeout=5（秒）+ hikari connection-timeout 5000 + query-timeout 2 三件套、影子表 staging+单事务三 RENAME 原子切换（主表零锁）、thin Math.max(2,max)；CI 外部终验读数由补记笔登记** |
| E18 | verify 补偿把异步 MQ 提交当成功转人工不可达 | publishSubmitted 返回值恒 true（onFailure 在回调线程晚于 return 求值），VerifyDegradeService 据此记成功 republished++，broker 不可达时永不转人工无告警 | 中 | LIGHT | **已封盘 TASK-256（2026-10-16：publishSubmittedSync syncSend 真值语义 + 两决策点改调，broker 故障不再假成功；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E19 | 好友批量同意自调用绕过 @Transactional | batchTransition 内直接调 accept(id, caller)，同类自调用不过事务代理（全仓无 exposeProxy/AopContext），update+insert 两写失原子且测试只断言注解存在 | 中 | FULL | **已封盘 TASK-256（2026-10-16：@Lazy self 委托批量路径经事务代理 + mock self 结构断言；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E20 | STOMP CONNECT 未校验 token type | jwtUtil.parse 只验签与时效（type 校验只在 parseRefresh），refresh 可替代 access 建 WS 会话（凭证窗口放大 672 倍），对照网关强制 type=ACCESS | 中 | FULL | **已封盘 TASK-256（2026-10-16：parseAccess type==ACCESS 对称校验 + 拦截器改调 + 三用例；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E21 | 框架 4xx 升格 500 + BizException 恒 200【合并】 | @ExceptionHandler(Exception) 兜底使 415/406/缺头变 500+ERROR 堆栈；BizException 无 @ResponseStatus 越权/服务不可用均 200，同码 1002 在网关/过滤器两处 HTTP 状态不一 | 中 | LIGHT | **已封盘 TASK-255（2026-10-16）：common 全局 handleBizException 加 HttpServletResponse 按码映射 1001→401/1002→403/3001→404（其余 200）+ 415/406/缺头三类框架 Handler；八个局部 handler 保留（机制收拢另立候选）；CI 外部终验读数由补记笔登记** |
| E22 | 申诉终判 DTO pass 无 @NotNull | 原生 boolean 漏传默认 false 静默走「维持拒绝」终判，verdict 回写+outbox 推送不可逆且无参数错误提示 | 中 | LIGHT | **已封盘 TASK-256（2026-10-16：DTO Boolean+@NotNull + 三处 Boolean.TRUE.equals + 缺 pass→400；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E23 | myStats/Goal COALESCE 使 idx_user_time 退化 | 列被函数包裹不可 sargable 按用户全量回表；GoalService 同款。**前提订正（2026-10-16 复验）**：selectDailyDistanceSince 非「全仓无调用（从未）」，系 TASK-232 引入在用、TASK-235 时间口径改写 COALESCE 后失用（现况 main 零调用，grep 实证） | 中 | LIGHT | **已封盘 TASK-255（2026-10-16）：纯索引路径——函数索引 (user_id,(COALESCE(start_time,created_at))) migrations 幂等+02 基线双路径，服务层与查询 SQL 零改动，失用方法已删；EXPLAIN 未实测登记（spec §5 预注册 4）；CI 外部终验读数由补记笔登记** |
| E24 | 改判重激活不重置 settled_at 归入旧周期 | updateStatus 只 SET status，range 归窗按 settled_at → 补回里程漏计当周/当月榜，与已归档日快照口径长期不一致 | 中 | LIGHT | **已封盘 TASK-255（2026-10-16）：新增 reactivate（SET status+settled_at=NULL）替换 applyVerified 重激活分支，改判里程归位当周/当月窗口，rollbackOnRejected/updateStatus 保留；CI 外部终验读数由补记笔登记** |
| E25 | R2 首有效点 speed=0 伪造加速度尖峰 | 首点 speed/dt 从未计算保持 0，以它作 prev 得 a=speed[1]/dt[1] 必超阈，minCount=3 实际门槛降为 2，高频采样轨迹被误拒且证据计数被污染 | 中 | MEDIUM | **已封盘 TASK-256（2026-10-16：R2 循环自 i=2 跳首区间 + 三判别用例；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E26 | 前端榜单筛选无取消陈旧响应覆盖 | loadLb 无 AbortController/请求序号，快速切换旧响应覆盖新态、finally 提前置 loading=false；feed/index/notifications 分页同款 | 中 | LIGHT | **已封盘 TASK-257（2026-10-17：四页面内联请求序号守卫 + leaderboard 快速切换 E2E 用例（延迟 3s 竞态实测 4.2s 稳定）；C-01 `033110a` / C-02 `5788184`，MEDIUM 档亲核三块 PASS，CI 读数由补记笔登记）** |
| E27 | 网关 actuator 白名单与路由表不一致探针 404 | 白名单声明 /actuator/health* 但 7 条路由无 /actuator/**，经网关探针恒 404；compose 裸 TCP 检查掩盖错配，K8s 按注释口径配探针即反复重启 | 中 | MEDIUM | 反证关闭（2026-10-16 批4a-1 侦察：GatewayProbeTest 真实网关全上下文断言 /actuator/health/liveness 与 /readiness 经网关端口 200/UP 且基线全绿——无 /actuator/** 路由时请求落入 actuator 端点映射而非 404，白名单为有效配置；compose 裸 TCP 检查仅风格项非缺陷） |
| E28 | X-Request-Id 无长度/字符校验 | resolveOrCreate 仅 trim，客户端可控值可挂他人链路/放大日志全链扩散；入口白名单一处收敛所有 MVC 服务与 MQ 续链；既有契约测试反而固化原样复用 | 中 | FULL | **已封盘 TASK-256（2026-10-16：双实现同规则镜像净化（长度 1..64 字符白名单否则 newId）+ 四类对称用例；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E29 | mapmatch 候选边并集复用放大 | acc.addAll 无去重、超限判据只看单叶非累计并集、每点每边重新 substring/parseDouble，CPU 毫秒→秒级超 verify 3s 读超时，并集 WKT 常驻堆无观测 | 中 | MEDIUM | **已封盘 TASK-255（2026-10-16）：LinkedHashSet 去重保序 + 累计判据告警（不截断行为不变）+ WKT 每边一次预解析，黄金值行为逐位一致，不做延迟声明；CI 外部终验读数由补记笔登记** |
| E30 | 昵称整行 updateById 丢失更新 | selectById→setNickname→updateById 整行回写含 passwordHash/role，无 @Transactional 无 @Version；与改密/撤销管理员并发可回写旧密码哈希或旧 ADMIN（毫秒级窗口，失败模式为安全语义回退） | 低 | MEDIUM | **已封盘 TASK-256（2026-10-16：LambdaUpdateWrapper 单列 set(nickname) + TableInfoHelper 预注册测试；C-01 `a89d095` / C-02 `2a2f11f` 独立复核五块 PASS 终裁 ACCEPT，CI 读数由补记笔登记）** |
| E31 | .trivyignore 与 CI 注释漂移无过期机制 | ci.yml 注释称「历史存量 13 项 CRITICAL 已登记」实际仅 2 条 CVE；声明「修复完成前不删除」无过期字段，豁免随时间永久化 | 低 | LIGHT | **已封盘 TASK-252（2026-10-16）：注释校准为 2 项含订正来源 + 两条 CVE 各补「复核期限 2027-04」纯注释** |

> E 系排除声明（本轮核审无问题面，防重复立项）：D 系已封盘 8 项零回归（D1-D8 逐项复查）；点赞幂等/flush 两写同事务/失败重放/deleteRecord 四表级联/pageFeed 可见性预过滤/updateVisibility 值域门/submit 20000 上限 500 批/归档幂等三步；网关过滤器全分支身份注入剥离、type=ACCESS、过滤器顺序、异常映射、CORS、7 路由对齐、actuator 收窄；MQ 四消费者 SETNX+表级双保险幂等、失败删键重投、%DLQ%、outbox SQL 均带 status 条件；好友 7 天惰性取消/乐观流转/批量回表；规则面两级缓存失效/判定幂等/灰度 floorMod/R1 R4 边界/R5 熔断；前端 HttpOnly cookie refresh/无 v-html/分页上限/批量防抖；基建 .env 未跟踪/11 份 migrations 列序一致/端口全绑 127.0.0.1/监控六 job 对齐；E2E 无固定 sleep；各服务 IT 真实中间件佐证。
> E 系诚实声明：◎ 标注为侦察代理行号级证据未经二次复核（立项前须复验，尤其 E13/E14/E17/E26/E29 需 EXPLAIN/真实库/真实网关复验，E16 需实测 Sentinel 库回调语义）；Nacos 运行时配置全部未核（影响 E15 适用性与 E16/E17 触发前提）；未启动任何服务，全部结论为静态阅读；E7/E17/E30 触发概率未量化；warehouse DDL 实况、api 枚举与 TINYINT 码位、Feign Fallback 分支、gateway/common IT 盲区、STOMP 越权订阅运行态均未核。

推荐序（E 系批次建议）：
1. **批1 已封盘 TASK-251（FULL 档，2026-10-16）**：E1+E2+E3 认证/鉴权面三处破口已收口（C-01 `124c93e` / C-02 `fd14459`，独立复核五块 PASS 零阻断，终裁 ACCEPT；CI run `38864817657` 全绿，完整闭环）；
2. **批2 已封盘 TASK-252（MEDIUM 档，2026-10-16）**：E4+E31 CI runner 迁移预备已收口（C-01 `66d0ec3` / C-02 `49b02c7`，MEDIUM 档指导亲核三块 PASS；钉 ubuntu-24.04 + 六 uses 升 v5/v6 + probe-ubuntu26 试跑通道 + concurrency/timeout + trivyignore 校准；ubuntu-26.04 真实试跑待 10-19 标签可用后手动 dispatch，读数随补记笔登记）；
3. **批3 已封盘 TASK-253（FULL 档，2026-10-16）**：E5（调度池并行化）+ E6（申诉终判兜底对账）+ E7（结算 ZREM 二次复核）+ E9（幂等迁移）四项收口（C-01 `1c35a27` / C-02 `22a0802`，白名单恰 10 文件跨 record/verify/leaderboard/sql 四面；指导亲核三块 PASS + 独立复核五块全 PASS 零阻断、N1-N4 非阻断登记，终裁 ACCEPT；surefire verify 182→188、leaderboard 104→108 只增不减；CI run 待观察、读数随补记笔登记）；
4. **批4（纵深与测试债）**：批4a-1（网关+高危簇）**已封盘 TASK-254**（2026-10-16：E10/E11/E12/E16 修法落地 + E27 反证关闭 + 两顺手项搭车，FULL 档，指导亲核 + 独立复核五块 PASS 终裁 ACCEPT，CI run `38892885813` 全绿外部终验达成完整闭环）；批4a-2 配置性能余项（E17/E21/E23/E24/E29）**已封盘 TASK-255**（2026-10-16：五项修法 R1-R7 落地 + E23 前提订正，FULL 档 16 项白名单，指导亲核 + 独立复核五块 PASS 零阻断 N1-N3 非阻断登记终裁 ACCEPT，CI run `38903763620` 全绿完整闭环）；批4b 测试可观测（12 项）已切分立项（2026-10-16）：**批4b-1 TASK-256 已封盘**（2026-10-16：E15+E18+E19+E20+E22+E25+E28+E30 八项修法 R1-R8 落地，FULL 档 25 项白名单（含 N-a 追认 VerifyControllerTest + N-c 计数订正），指导亲核 + 独立复核五块 PASS 零阻断 4 项非阻断登记终裁 ACCEPT，CI run `38917734857` 全绿完整闭环）+ **批4b-2 TASK-257 已封盘**（2026-10-17：E8+E14+E26 前端测试可观测三项落地，MEDIUM 档 20 项白名单（10+条件项 8+台账 2），条件项三块（assertNoUnmocked 接入 / 基线红订正 3 断言 / 补 mock 三端点）C-01 前定档登记，指导亲核三块 PASS，CI 读数由补记笔登记）+ **E13 拆出**（真实 PostGIS 库复验路径独立成课题拟 TASK-258——验证路径异质（scratch 容器实测 vs 纯 JVM）且结果未知可能引出修复批，批4b-2 已封盘可启动立项侦察）；◎ 标注 8 项已逐项亲验坐实，证据入 TASK-256/257 spec §1。**E 系批1/批2/批3/批4a-1/批4a-2/批4b-1/批4b-2 全部收口完毕，31 项候选全部闭环**。



