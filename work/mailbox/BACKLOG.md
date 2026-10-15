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
| D1 | 总榜二级缓存与入榜/回滚一致性 | `@Cacheable leaderboard:overall` 双层 TTL=5min（CacheConfig L49），`applyVerified`/`rollbackOnRejected` 改 ZSet 后从不 evict——删除/驳回回撤后总榜最长 5min 显示旧里程，新入榜最长 5min 不出现 | 高 | MEDIUM | 候选（推荐与 D3 合批） |
| D2 | appeal 待审队列 status 索引补齐 | `appeal` 表仅 id PK + uk_record，`listPendingAppeals` WHERE status=PENDING ORDER BY id 全表扫描（对比 outbox 表已有 idx_status_id） | 中 | LIGHT | 候选 |
| D3 | 结算对账 scan 游标续用 | `settleAndReconcile` 清残留成员每页重开 `opsForZSet().scan()` 不续 Redis cursor，总榜上万成员时 O(N²) 重复全量扫描隐患 | 中 | LIGHT/MEDIUM | 候选（推荐与 D1 合批） |
| D4 | 改密全量吊销 KEYS 扫描替换 | `AuthService.java:418` `stringRedisTemplate.keys(auth:refresh:{uid}:*)` 全 keyspace 阻塞扫描，宜改 SCAN 渐进迭代 | 中 | LIGHT | 候选 |
| D5 | WS STOMP 端点 Origin 纵深 | `WebSocketNotificationConfig.java:46` `addEndpoint` 未 setAllowedOrigins；CONNECT 帧鉴权兜底非数据泄露，属纵深加固 + 生产姿态验证项 | 中 | LIGHT | 候选 |
| D6 | mapmatch 候选集数量上限防护 | `MapMatchService.java:88-99,135-148` 跨大片区域 bbox 过宽时 ST_DWithin 候选边可能巨量全拉 JVM 逐点垂距，无上限/耗时防护 | 低-中 | MEDIUM 小 | 候选 |
| D7 | 点赞对账全表拉取分批化 | `RecordLikeService.java:514` `selectRecordLikePairs()` 每 10min 整表 (record_id,user_id) 拉进 JVM 内存 | 低 | LIGHT | 候选 |
| D8 | R5 降级窗口可观测告警 | `R5OffRoadRule.java:67-77` mapmatch 熔断期降级「不命中」为有意 tradeoff 非缺陷；建议补「R5 降级期间通过率突变」监控告警防误判 | 低（提示） | LIGHT | 候选 |

> 侦察排除声明（防重复立项）：Outbox 毒丸隔离、规则灰度一致性、verify 判定幂等 + record 状态机乐观锁、好友并发互加/解除、点赞幂等、网关鉴权/CORS/治理凭证、track_point 分片索引、前端契约/WS 队列名均已逐文件核审无问题；D 系静态推论项（D2/D3/D6/D7）真实影响需立项后 EXPLAIN/压测确认。

推荐序：
1. **D1+D3 leaderboard 一致性与对账治理批**（MEDIUM）：总榜缓存 evict/失效联动 + 结算 scan 游标续用，同处 `LeaderboardService`/`CacheConfig` 合批减少重复触达，数据一致性优先；
2. **D2 申诉待审索引**（LIGHT）：纯 SQL 幂等迁移即可，独立小课题；
3. **D6 mapmatch 候选集上限**（MEDIUM 小）：提升型防护，非 P0；
4. **D4+D5+D7+D8 卫生与纵深批**（LIGHT）：SCAN 替换 + WS Origin + 点赞对账分批 + R5 降级告警，可合并一批。



