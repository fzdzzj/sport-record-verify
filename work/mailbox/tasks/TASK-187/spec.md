# TASK-187 add-notification-preference 任务书（通知偏好设置·按类型开关）

## 0. 红线（违任一条即 FAILED 停手回报）

1. 闸门位置已裁决：createNotification 落库前单一权威闸门（不得改为 relay 投递前过滤或 controller 层过滤）；被拦截类型不落库、不推送、不计未读
2. 既有 REST 契约零改动：列表/未读数/单条已读/全部已读接口语义不动；只新增 GET/PUT /api/notifications/preferences 两端点
3. WS 管道零触碰：notificationWs.ts / NotificationPushRelay / NotificationReadReceipt / 已读回执钩子（markRead / markAllRead）全部零改动
4. sql/01-user-db.sql 只追加 notification_preference 新表，notification 等既有表定义零改动；禁 Flyway / Testcontainers
5. 零新依赖：后端零 pom、前端零 lockfile；ci.yml / compose / gateway-service / mvn-verify.sh / mailbox-contract.sh 零触碰
6. NotificationType 常量集合零扩；偏好只对既有三类做开关
7. 前端零新页面：typed-router.d.ts / App.vue 零触碰（typed-router 应零漂移）
8. 词面门正则字面量不入任何 tracked 文档与输出（TASK-184 F1 / TASK-185 D3 教训）
9. 停止条件：需触碰上述任一零触碰面才能完成 / offline 基线 512（36/41/95/127/144/59/10）回退或 811 增 / 契约门在途 rc=1 无合理解释 / upsert 无法用 PK 原生实现

## 1. 背景与侦察实证（指导侧已核）

- 通知类型常量三类已在（[NotificationType.java](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/enums/NotificationType.java)：RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED），零扩
- 两调用方均 fire-and-forget 忽略 createNotification 返回值（[NotificationEventConsumer.java L194/L198](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/mq/NotificationEventConsumer.java)、[FriendService.java L189](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/service/FriendService.java)）——返回语义扩展安全
- 建表脚本模式：notification 表在 [sql/01-user-db.sql L51](file:///d:/code/sports/sql/01-user-db.sql)，新表同文件追加
- web 无设置页（12 个 .page.vue 无 settings/profile）；偏好卡片内嵌通知页为预注册裁决
- Controller 模式：NotificationController @RequestMapping("/api/notifications") + X-User-Id 鉴权口径

## 2. 预注册实施设计

### 2.1 DB（sql/01-user-db.sql 追加）

```sql
CREATE TABLE IF NOT EXISTS `notification_preference` (
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `type`       VARCHAR(30) NOT NULL COMMENT '通知类型（NotificationType 三类）',
    `enabled`    TINYINT     NOT NULL DEFAULT 1 COMMENT '是否接收：1 开启，0 关闭',
    `updated_at` DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`user_id`, `type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知偏好表';
```

缺行 = 默认开启（读取侧补默认，无预填充任务）。

### 2.2 后端（user-service）

- `entity/NotificationPreference`：@TableName("notification_preference")，复合主键 @TableId 不适用——MyBatis-Plus 复合键用 mapper XML/注解 selectByPrimaryKey 或 service 层 wrapper 点查（沿项目 mapper 风格，禁引新插件）
- `mapper/NotificationPreferenceMapper`：BaseMapper；upsert 用 @Insert 注解 `INSERT ... ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_at = VALUES(updated_at)`（或项目既有 XML 模式，二者择一沿先例）
- `service/NotificationPreferenceService`：
  - `getPreferences(userId)` → `List<NotificationPreferenceView>`（三类全量视图，DB 缺行补 enabled=true）
  - `upsert(userId, type, enabled)` → ON DUPLICATE KEY UPDATE（行级原子）；updated_at 服务端时间
- `NotificationService.createNotification` 首行：偏好关闭 → `return false`（不 insert、不 publish）；javadoc 返回语义扩展（false = 幂等已存在 **或** 偏好关闭）；偏好查询与通知同库（不预设 fail-open 分支）
- `NotificationController`：`GET /preferences` 返回 View 列表；`PUT /preferences` 接收 body（沿既有 record DTO 风格，含 type + enabled 列表或三项 map——贴既有 dto 包模式定形）；两端点 X-User-Id 缺失拒绝沿既有口径
- `dto/NotificationPreferenceView`：type / enabled / updatedAt 或最小 type+enabled（贴 NotificationView 风格）

### 2.3 前端（web）

- `client.ts` 尾部纯追加：`NotificationPreferenceView` interface + `getNotificationPreferences()` + `updateNotificationPreferences(prefs)`（PUT，/user/api/notifications/preferences 前缀，不传 userId）
- `notifications.page.vue`：列表区下追加「通知偏好」卡片——三类中文标签（记录通过 / 记录驳回 / 好友通过）各一 a-switch + 保存按钮；onMounted 登录态拉取；保存成功 message 提示并按返回刷新；失败沿 `[code] message` a-alert 内规；未登录不发请求（loggedIn 门控同列表）

### 2.4 单测矩阵（offline 确定性，预计 +10 上下）

| 类 | 断言 |
| --- | --- |
| NotificationPreferenceServiceTest（新） | getPreferences 缺行补默认全 true；已有行覆盖默认；upsert 插入新行；upsert 重复同值幂等（第二次为 update 无新行） |
| NotificationService 偏好闸门（并入既有 NotificationServicePushTest 或新类，二择一登记） | 偏好关闭 → insertIgnore 零调用 + relay publish 零调用 + 返回 false；缺省/开启 → 落库 + publish + 返回 true |
| 端点契约 | 按 user-service 既有 controller 测试先例（有则沿，无则 service 层覆盖并登记） |

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/add-notification-preference/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-187/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-187 通知偏好提案与任务书`
- C-01：`sql/01-user-db.sql`、`NotificationPreference.java`（新）、`NotificationPreferenceMapper.java`（新）、`NotificationPreferenceService.java`（新）、`NotificationService.java`（首行闸门 + javadoc）、`NotificationController.java`、`NotificationPreferenceView.java`（新，dto）+ PUT 请求 DTO、`web/src/api/client.ts`（尾部纯追加）、`web/src/pages/notifications.page.vue`、单测类（新 1~2 个 + 既有扩展），主题：`feat(user): 通知偏好按类型开关闸门与设置端点（TASK-187）`
- C-02：tasks.json 全勾 + 本 spec §7 纯追加 + `work/mailbox/tasks/TASK-187/handoff.md` + `work/mailbox/PLAN.md` 纯追加，主题：`docs(mailbox): 登记 TASK-187 通知偏好验收与台账闭环（TASK-187）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-186 收口实测 1972 参照）；收口读数以**收口态实测**为准。只增不减。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态（正则字面量不入任何 tracked 文档与输出）；`git diff --check` rc=0；契约门在途 `--open TASK-187 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整
2. **收口门禁（C-02 后亲跑留证）**：Java offline 全量新基线逐位登记 + `--static=record-service` 811 不增；**scratch DB 验证**：notification_preference 建表 DDL 在 scratch 库执行成功 + upsert 行为断言（插入新行 / 重复同值 update 无新行 / 缺行读取默认语义由 service 单测覆盖）——沿 TASK-182 scratch 库口径；web 三件套（type-check rc=0 / build rc=0〔TMP 指仓内 .trae/esbuild-tmp〕/ typed-router 零漂移）+ `pnpm --dir web install --frozen-lockfile` rc=0；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（闸门三态读数、偏好 service 读数、scratch DB 留证）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）
4. **真实链路联调（可选不判失败）**：本机起服务后浏览器验证偏好开关生效；不可达则 UNDETERMINED（沿 182/185/186 口径）

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`aa7e6aa1825b30ae0e4434fdc5954f628d7a1ecf`（`aa7e6aa`）`docs(spec): 派发 TASK-187 通知偏好提案与任务书`
- C-01 实施笔：`7472774e9c2bc89e101b631f7c8d331605c5ad49`（`7472774`）`feat(user): 通知偏好按类型开关闸门与设置端点（TASK-187）`
- C-02 台账笔：`（由回传报告以显式哈希给出，见 handoff §8）` `docs(mailbox): 登记 TASK-187 通知偏好验收与台账闭环（TASK-187）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测）

| 用例类 | 断言内容 | 读数 |
| --- | --- | --- |
| `NotificationPreferenceServiceTest` | 缺行补默认全 true；已有行覆盖默认；upsert 插入新行；upsert 重复同值幂等；批量更新返回更新后全量视图；偏好闸门三态判定（缺行/开启返 true、关闭返 false） | 8 run, 0 fail |
| `NotificationServicePushTest` | 偏好关闭 → insertIgnore 零调用 + relay publish 零调用 + 返回 false；偏好开启 → 落库 + publish + 返回 true；偏好服务缺省 → 兼容开启落库 + publish + 返回 true | 12 run, 0 fail（含既有 9 条 + 偏好 3 条） |
| `NotificationControllerTest` | 偏好查询（auth=false 显式携带命中服务）；偏好更新（auth=false 显式携带命中服务）；偏好查询（auth=true 缺失 X-User-Id 拒绝 401）；偏好更新（auth=true 缺失 X-User-Id 拒绝 401） | 14 run, 0 fail（含既有 10 条 + 偏好 4 条） |

### 7.3 web 三件套读数（收口态）

| 项 | 读数 | rc |
| --- | --- | --- |
| type-check | `vue-tsc --noEmit` 无输出 | 0 |
| build | `vite build` ✓ built（TMP 工作区化） | 0 |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |

### 7.4 Java 新基线与 811

- offline 全量：`36/41/110/127/144/59/10` = **527**（user-service 95→110，只增不减，净增 15；其余六模块逐位不变，总数较 512 净增 15）。
- `--static=record-service` Checkstyle **811 持平**（未增，rc=1 预期）。
- 本任务零新依赖（后端零 pom 改动、前端零 lockfile 改动）。

### 7.5 scratch DB 验证留证

- MySQL 8.0 容器环境执行 `notification_preference` 建表 DDL 成功。
- 首次插入新行：`ROW_COUNT() = 1`。
- 重复同值 upsert：`ROW_COUNT() = 0`，`COUNT(*) = 1`（无新增行，行级原子幂等）。
- 值翻转 update：`ROW_COUNT() = 2`，更新为目标值 `enabled = 1`。

### 7.6 token 前后读数

- 开工实测 SUM=**1972**（口径 repo 全量 `git grep -cF`，29 项逐值见 handoff §7）。
- 收口实测只增不减（收口态实测 SUM=**1972**，见 handoff §7）。

### 7.7 联调留证

本机真实链路（网关 + user-service + Redis + 浏览器偏好开关）因中间件全栈未起未展开，登记为 **UNDETERMINED**（沿 TASK-182/185/186 口径，不判失败）；链路语义等价由单测及 scratch DB 验证覆盖。

### 7.8 未覆盖项与说明项

1. **不追溯补发历史**（说明项）：偏好重新开启仅对未来通知生效，不追溯补发历史。
2. **SETNX 24h 窗口边界**（说明项）：MQ 判定事件被偏好闸门拦截后，Redis SETNX 已置 24h 去重标记；24h 内若用户开启偏好且 MQ 发生重试投递，由于 SETNX 尚未过期将不再重投落库，登记为说明项。
3. **真实链路联调 UNDETERMINED**：中间件不可达，沿 182/185/186 口径登记。

### 7.9 三支裁决

**PASSED**：后端实体+Mapper+Service+闸门+端点全部落地 + 前端 Client+Vue 偏好卡片接入 + 单测矩阵全绿（user-service 95→110，全仓 512→527）+ web 三件套全绿 + 静态 811 持平 + scratch DB 留证齐备 + 全门禁通过。无 FAILED 项。外部终验待推送后下一次外部门槛（第 29 次）CI 绿。

## 8. 开工读数（指导侧派发时基线）

- 派发笔基线 HEAD `10f46eb`（TASK-186 第 28 次门槛补记笔）；`origin/main...main` = `0 1`；工作树仅本任务在途派发材料
- offline 512（36/41/95/127/144/59/10）；static 811；token SUM 开工实测为准（收口态参照 1972）；契约门/词面门开工清
