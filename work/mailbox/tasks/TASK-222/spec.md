# TASK-222: Friend Activity Feed (P4)

派发基线：TASK-221 收口后（含 D7 修复笔）；开工前工作树干净、offline 基线以派发笔时点为准、token 29 项 SUM 持平记录。提案三件套见 spec/changes/add-friend-feed/（proposal.md / spec-delta.md / tasks.json）。

## S0 hard constraints

- 红线零触碰：六服务白名单外 src/main、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/ 既有文件、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts（生成物）。
- 零 schema：无新表、无新列、无迁移、无新索引（user_id 索引沿 TASK-211 已知限制单列议题，不在本课题解禁）。
- 既有 visibility 门零弱化：detail/points/verify-result/like 三端点与 assertCanView 语义一行不改；feed 预过滤须给出与门语义等价的论证（R6）。
- 真实凭证零入库（token 29 项 SUM 只减不增、实际持平）；命令行禁中文；MSYS sed -i 禁对 tracked 文件。
- 测试隔离纪律（TASK-221 D7 教训）：新增纯 Mockito 单测凡解析 MP wrapper SQL（getSqlSegment 等）必须沿仓内 TableInfoHelper.initTableInfo 惯用法；每个新增测试类须以 `-Dtest=XxxTest` 隔离单跑通过（CI online 类序无关）。

## S1 verified context (guidance-side recon, 2026-10-13)

- friendship 表规范化存储 user_low<user_high 复合主键 + createdAt，无 status 列（仅 ACCEPTED 落库）；FriendService.listFriends 已有双向还原分页；InternalFriendController 现有 /requests 三端点 + GET list（PageResult<FriendDTO>）+ GET /exists（TASK-221）——无「好友 ID 列表」面，需新增 /internal/friends/ids。
- 作者昵称面：recon 确认 user-service 是否已有批量内部用户摘要端点；有则复用（UserApi 增方法即可），无则新增 /internal/users/briefs（入参 List<Long>，出参 userId+nickname）。
- record-service：pageMyRecords 模式 = selectPage(Page, eq(userId).orderByDesc(createdAt))，/my 分页参数钳制同款复用；SportRecord 已有 visibility 列（0/1/2）与 RecordSummaryDTO（含 visibility）。
- degrade 语义：resolveUserId 在 auth.enabled=false 时返回 null；assertCanView caller=null 放行（TASK-204 零扰动先例）——feed 为新增面，null caller 取 PUBLIC-only 全站时间线。
- web：pages 目录 + 客户端 client.ts 集中 API 层 + typed-router 生成物；visibility 徽标组件已在 record 列表页存在，复用。

## S2 preregistered design

- 端点：GET /api/records/feed?page=&size=（record-service，RecordController）。identity 门与 /my 同构（resolveUserId；缺头 401 code 1001）。
- 查询语义（单查无 join）：auth on 时 user_id IN (self ∪ friendIds) AND (user_id = self OR visibility IN (0,1))，createdAt DESC，分页钳制同 /my（page>=1, size 1..50 或既有钳制值，recon 后定稿）。friendIds 一次内部调用拉全量（IN 子句）。
- degrade（caller=null）：visibility = 0（PUBLIC）全站时间线，不区分归属。
- 跨服务：api 模块 UserApi 增 listFriendIds(Long userId)→List<Long> 与 listUserBriefs(List<Long>)→List<UserBriefDTO>（或复用既有返回型）；UserApiFallback 两方法软降级空列表（fail-safe：feed 退化为仅自己；昵称回退 userId 字符串渲染，不阻断不报错）。
- DTO：FeedItemDTO（RecordSummaryDTO 全字段 + authorUserId + authorNickname），昵称批量解析仅对当页 distinct user_id。
- web：/feed 路由页（卡片=作者昵称+可见性徽标+类型/距离/时长摘要+相对时间）、导航入口「动态」、页脚/按钮分页沿既有页风格；client.ts 增类型与调用。
- 不做：fan-out/MQ/事件、feed 内联点赞（卡片链到详情页）、探索页、可见性编辑、榜单联动。

## S2.5 discriminants

- R1 过滤与排序：auth on feed 恰含 own(任意 visibility) + friends(PUBLIC/FRIENDS)，createdAt DESC；陌生人记录即使 PUBLIC 也不入 feed。
- R2 degrade：caller=null → PUBLIC-only 全站；缺认证头 → 1001（/my 同构）。
- R3 好友 ids：/internal/friends/ids 双向还原（本人是 low 端取 high、high 端取 low）；UserApi fallback 空 → feed 仅自己（fail-safe 不崩）。
- R4 作者面：briefs 批量昵称；fallback 空 → 昵称渲染 userId 字符串；当页 distinct user_id 才发起调用。
- R5 web：feed 页卡片要素齐 + nav 入口 + 分页；type-check/build/test 承载。
- R6 门不弱化：既有读取面门零 diff；feed 预过滤与 assertCanView 在 feed 场景下等价性论证（own 任意态=owner 放行；friends PUBLIC/FRIENDS=门放行子集；陌生人不入集=门 403 子集的规避）。
- G1 offline 逐位只增不减（user/record 净增，其余五模块逐位不变）；G2 只改清单全等；G3 FULL 门禁含 CI 外部终验。

## S3 stop conditions（任一触发即停，上报指导侧裁决）

1. S1 recon 与代码实况不符（已有 feed 面 / 昵称批量面语义冲突 / 分页钳制不一致）。
2. offline 任何模块测试数下降或新增后模块位和异常。
3. 降级与 fail-safe 口径与既有先例冲突（degrade 零扰动 / fallback fail-safe 家法）。
4. 发现 feed 可能绕过 visibility 门或引入新的读取面暴露。
5. 性能红旗：friendIds 规模异常（>1000）或查询全扫致测试超时 → 停止并登记索引解禁议题。

## S4 whitelist（目标 C-01 恰 N 文件，recon 后定稿锁死）

预估 16-18：api 3（UserApi、UserApiFallback、UserBriefDTO 若需）/ user-service 4（InternalFriendController 或新 InternalUserController、FriendService、测试 2）/ record-service 5（RecordController、SportRecordService、FeedItemDTO、测试 2）/ web 4-5（feed 页、client.ts、导航或布局、测试增补）/ README 1。C-02 台账 4 文件（tasks.json、spec.md、handoff.md、PLAN.md）另计。定稿后判据 B 逐字符全路径（TASK-221 D6 教训：禁缩写）。

## S6 gates (FULL tier)

- offline 全量（唯一入口 scripts/verify/mvn-verify.sh --mode=offline test）rc=0 逐位留证；新增测试类逐个 `-Dtest=` 隔离单跑留证（D7 教训落地）。
- --static=<改动模块>（仅声明三件套的模块）；web 四件套（test/type-check/build/typed-router diff，TMP 重定向仓内 .trae/tmp）。
- 契约门双态（在途 --open TASK-222 --baseline=<派发笔> + 收口无参）；token 29 项基线/工作树 SUM 持平；词面门 CI-exact 改动集 ZERO_HIT + 探针阳性对照；git diff --check；行尾（新文件纯 LF+末尾换行无 BOM，改文件保基线）。
- 独立复核四块（用户派发）；指导侧亲核后推送，CI 外部终验绿为收口前提。

## S7 backfill (after C-02)

C-02 回填本节：判别式红绿、门禁留证、偏差 D1-Dn（含 D7 类隔离性问题的如遇）、未覆盖清单（真实浏览器交互/跨服务真实联调沿先例口径）。handoff.md §2 只改清单用 git 全路径逐字符（禁缩写、禁散文 token）。
