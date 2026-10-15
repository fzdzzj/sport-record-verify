# TASK-245: 前台端到端业务与全功能体验增强闭环——记录详情指标与可见性修改、申诉全链路进度条、用户资料与好友批量、动态流视觉与GPX导入（MEDIUM 档）

> **开工基线**：6f4d766（main 分支，工作树干净，TASK-245 方案 D 立项准备基线）
> **任务级别**：MEDIUM（前台全页面端到端闭环体验升级 + 运动记录/社交/申诉全生命周期贯通）

## 1. 目标与背景

- **背景与痛点**：
  1. **记录详情页指标残缺与可见性不可修改（B22 + B29）**：
     - `web/src/pages/record-detail.page.vue` 仅加载并渲染 SVG 轨迹折线，完全不显示最基础的运动摘要信息（里程、用时、平均配速、运动类型）；
     - `record-service` 在记录提交后无任何修改可见性的接口，用户误设隐私或想分享好友时只能删除整条记录重传；
     - SVG 轨迹图将所有点渲染为单一尺寸的小红圆点，无法分辨起点与终点的行进方向。
  2. **逆向申诉流程断层与误导性管理外链（B26）**：
     - 用户在 `web/src/pages/verdict.page.vue` 提交申诉后，页面竟展示跳往管理后台的链接 `<router-link to="/admin-appeal">去管理后台审核 →</router-link>`，普通用户点击必遭 403 越权被拒；
     - 提交后若用户刷新页面，无法识别当前记录已在申诉中（`status=4`），重复点击提交会触发 `3005 唯一键冲突` 报错；用户无法查看申诉审批流转与审核建议。
  3. **社交关系信息冰冷与个人资料维护缺失（B25）**：
     - `web/src/pages/friends.page.vue` 的待处理好友申请表格中，申请人只展示冷冰冰的数字 ID（`fromUser: 10001`），完全无法确认对方是谁；且只能逐条手动同意/拒绝，缺乏批量处理；
     - `user-service` 无公开用户信息修改接口，`web/src/pages/settings.page.vue` 只有密码与通知偏好，用户无法查看或修改自己的昵称。
  4. **动态流视觉粗糙与冷启动转化断绝（B27）**：
     - `web/src/pages/feed.page.vue` 卡片无头像，运动类型显示生硬英文大写（`RUNNING`）；空态仅有一行留白文字，新用户无任何行为引导（如去加好友或去跑步）。
  5. **GPX 轨迹导入缺失（B28）**：
     - 系统此前仅支持纯前端 GPX 导出（TASK-218），提交记录页（`record-submit.page.vue`）仅能加载内置 10 点样例轨迹，缺乏导入外部 `.gpx` 文件的能力。

- **目标**：
  1. **B22 + B29 运动记录详情与可见性全能卡**：
     - 后端 `record-service` 新增 `PATCH /api/records/{id}/visibility`（仅允许本人修改）与基础信息端点；
     - 前端 `record-detail.page.vue` 补全运动指标卡片（距离、用时、配速、运动类型），支持原地修改可见性（公开/仅好友/私密）；
     - SVG 轨迹首点高亮为绿色起点（🟢 起点）、末点高亮为红色终点（🔴 终点），使运动行进方向一目了然。
  2. **B26 申诉逆向全链路透明追踪**：
     - 后端 `record-service` 新增 `GET /api/records/{id}/appeal` 申诉信息端点（带本人归属校验）；
     - 前端 `verdict.page.vue` 彻底移除跳往管理后台的外链，引入 `<a-steps>` 进度条（已提交 → 人工复核中 → 终判结果与审核意见）；已存在申诉单时自动回显并禁用重复提交（杜绝 3005 冲突）。
  3. **B25 用户资料维护与好友申请识别批量**：
     - 后端 `user-service` 新增 `GET /api/user/profile` 与 `PUT /api/user/profile`（更新个人昵称）；好友申请列表补填申请人真实昵称；新增批量同意与批量拒绝端点；
     - 前端 `settings.page.vue` 增加「个人资料」卡片支持查看/修改昵称；
     - 前端 `friends.page.vue` 申请列表展示申请人真实昵称，开启行多选支持一键「批量同意」与「全部忽略」。
  4. **B27 动态流视觉升级与空态引导**：
     - 前端 `feed.page.vue` 卡片增加首字主题彩色头像，运动类型中文徽标化（🏃 跑步 / 🚴 骑行 / 🚶 健走）；空态提供「去添加好友」「去记录运动」转化按钮。
  5. **B28 轨迹 GPX 文件前端导入解析**：
     - 前端 `web/src/utils/gpx.ts` 补齐 `parseGpx(xmlString): TrackPointDTO[]`；
     - `web/src/pages/record-submit.page.vue` 增加「导入 GPX 文件」按钮，支持本地 `.gpx` 文件自动解析填入并自动推导起止时间与里程，与既有 GPX 导出形成双向闭环。

## 2. 修改白名单

严格限定在以下 19 个文件内（恰 17 个代码/测试/组件文件 + 2 个台账文件）：

- 后端 record-service（共 3 文件）：
  - `record-service/src/main/java/com/sportverify/record/controller/RecordController.java`
  - `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
  - `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
- 后端 user-service（共 4 文件）：
  - `user-service/src/main/java/com/sportverify/user/controller/FriendController.java`
  - `user-service/src/main/java/com/sportverify/user/controller/UserController.java`
  - `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
  - `user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java`
- 前端 web（共 10 文件）：
  - `web/src/api/client.ts`
  - `web/src/api/__tests__/client.test.ts`
  - `web/src/utils/gpx.ts`
  - `web/src/utils/__tests__/gpx.test.ts`
  - `web/src/pages/record-detail.page.vue`
  - `web/src/pages/verdict.page.vue`
  - `web/src/pages/settings.page.vue`
  - `web/src/pages/friends.page.vue`
  - `web/src/pages/feed.page.vue`
  - `web/src/pages/record-submit.page.vue`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-245/spec.md`
  - `work/mailbox/tasks/TASK-245/handoff.md`

> **红线约束**：
> - 除上述白名单外，`verify-service`、`leaderboard-service`、网关、`package.json`、根/子 `pom.xml`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖。

## 3. 核心设计与判别式要求

### 3.1 B22 + B29 记录详情全能卡与可见性修改（record-service + web）
- **RecordController**：
  - 新增 `PATCH /api/records/{id}/visibility`：接收 `@RequestParam("visibility") Integer visibility` 或 JSON body；调用 `sportRecordService.updateVisibility(userId, id, visibility)`；
  - 仅本人可修改，非本人返回 403（`FORBIDDEN`）；值仅限 0（PUBLIC）、1（FRIENDS）、2（PRIVATE），非法值返回 400（`BAD_REQUEST`）；
- **SportRecordService**：
  - 实现 `updateVisibility(userId, recordId, visibility)`：更新主表 `sport_record` 的 `visibility` 字段；
- **前端 record-detail.page.vue**：
  - 补齐运动基本指标卡：运动类型中文标签（🏃 跑步 / 🚴 骑行 / 🚶 健走）、里程（km）、耗时（格式化为分秒或时分秒）、平均配速（min/km）；
  - 当前可见性标签及原地修改下拉（仅本人可编辑）；
  - SVG 轨迹图高亮起终点：首点为绿色圆点（`fill="#52c41a"`，`r="5"`），末点为红色圆点（`fill="#ff4d4f"`，`r="5"`），中间点为细小点。

### 3.2 B26 申诉逆向全链路透明追踪（record-service + web）
- **RecordController**：
  - 新增 `GET /api/records/{id}/appeal`：查询该记录对应的申诉单信息（仅本人或管理员可查）；
- **SportRecordService**：
  - 实现查询申诉单信息并返回申诉状态、申诉理由、终判审核结论及处理时间；
- **前端 verdict.page.vue**：
  - 彻底移除普通用户不可用的 `<router-link to="/admin-appeal">去管理后台审核 →</router-link>`；
  - 引入 `<a-steps>` 进度条：
    - 步骤 1：已提交申诉
    - 步骤 2：人工排队复核中
    - 步骤 3：终审完成（改判通过 / 维持原判）
  - 若已处于申诉中（`status=4` 或已有申诉记录），自动展示当前申诉进度与管理员审核结论，表单输入框置为只读/禁用并隐藏提交按钮，防止 3005 冲突。

### 3.3 B25 用户资料与好友申请批量处理（user-service + web）
- **UserController**：
  - 新增 `GET /api/user/profile` 与 `PUT /api/user/profile`（入参 `{ "nickname": string }`，更新本人昵称）；
- **FriendController & FriendService**：
  - `listPendingRequests` 响应中回填申请人真实昵称（通过 `userMapper` 批量补齐）；
  - 新增 `POST /api/friends/requests/batch-accept`（入参 `List<Long> requestIds`，批量同意）；
  - 新增 `POST /api/friends/requests/batch-reject`（入参 `List<Long> requestIds`，批量拒绝/忽略）；
- **前端 settings.page.vue**：
  - 新增「个人资料」卡片：显示当前 userId 与昵称，支持修改昵称并保存；
- **前端 friends.page.vue**：
  - 待处理好友申请表格中展示申请人真实昵称；
  - 表格开启行多选，提供「批量同意」与「全部忽略」按钮。

### 3.4 B27 动态流视觉与空态引导（web）
- **前端 feed.page.vue**：
  - 卡片头部渲染基于作者昵称首字的彩色头像（`<a-avatar>`）；
  - 运动类型标签中文徽标化（🏃 跑步 / 🚴 骑行 / 🚶 健走）；
  - 空态使用丰富卡片展示，提供「去添加好友」(/friends) 与「去记录运动」(/record-submit) 快速引导转化按钮。

### 3.5 B28 轨迹 GPX 文件前端导入解析（web）
- **前端 gpx.ts / gpx.test.ts**：
  - 实现 `parseGpx(xmlString: string): TrackPointDTO[]`：
    - 解析 XML 中的 `<trkpt lat="..." lon="...">` 节点与 `<time>` 文本；
    - 转换为标准 `TrackPointDTO[]`，自动补齐 `seq`，按时间戳计算 `speed` 与 `ts`；
    - 空或非法 GPX 抛出格式错误；
  - 单测覆盖合法 GPX 解析、时间戳提取与非法输入防御；
- **前端 record-submit.page.vue**：
  - 轨迹区域新增「导入 GPX 文件」按钮（支持 `.gpx` 文件选择）；
  - 选择后调用 `parseGpx` 解析并直接填充入 `points`，自动更新起止时间、耗时与里程推导字段。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. 后端验证：
     - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am`（BUILD SUCCESS，全用例通过）；
  2. 前端验证（在 `web` 目录下执行）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：全部用例全绿通过；
  3. 契约门禁：
     - `bash scripts/verify/mailbox-contract.sh --open=TASK-245 --baseline=6f4d766`（退出码 0，两件套齐备且白名单一致）；
  4. `git diff --name-only 6f4d766` 严格全等于上述白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(experience): TASK-245 前台端到端业务与全功能体验增强闭环（C-01 实施笔）`
  - C-02 台账笔：`docs(mailbox): TASK-245 前台端到端业务与全功能体验增强闭环验收与台账闭环（C-02 台账笔）`

## 5. 验收与门禁回填（实施回传）

- **后端单元测试**：✅ `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am` → BUILD SUCCESS，`Tests run: 298, Failures: 0, Errors: 0, Skipped: 0`
- **前端类型门禁**：✅ `web && pnpm run type-check` → 0 错误
- **前端单测门禁**：✅ `web && pnpm test` → `Test Files 4 passed (4)` / `Tests 55 passed (55)`
- **契约门禁**：✅ `bash scripts/verify/mailbox-contract.sh --open=TASK-245 --baseline=6f4d766` → 退出码 0（判据 A 两件套齐 + 判据 B 清单一致）
- **提交记录**：
  - C-01 实施笔：`feat(experience): TASK-245 前台端到端业务与全功能体验增强闭环（C-01 实施笔）`
  - C-02 台账笔：`docs(mailbox): TASK-245 前台端到端业务与全功能体验增强闭环验收与台账闭环（C-02 台账笔）`
- **红线复核**：✅ 白名单外文件零触碰；经主 Agent「方案 B」决策放宽 api 模块 2 文件（`FriendRequestDTO`、`SportRecordDTO`），实际改动集 = 19 白名单 + 2 api = 21 文件（与 handoff.md「改动清单」一致）；零新依赖、零 push、零 PR。

> 台账详情见 `work/mailbox/tasks/TASK-245/handoff.md`。
