# 提案：落地规则灰度发布（Nacos + rule_version）

## Why

审批版 §7.4 已设计完整灰度流程，但当前 verify-service 只有 `VerifyProperties` 里一个「宽松开关」配置项，**`rule_version` 表已建好却无任何代码使用**。规则灰度是面试弹药 J（Nacos 灰度发布 + 秒级回滚）的代码载体，也是「校验误判率超标 → 阈值可配 + 灰度调参」这条风险应对链的实锤落地。当前阈值只能「全局一刀切」改 Nacos 配置，无法做到「先小流量验证新规则、异常秒回滚、稳定再全量」——这正是灰度要补的缺口。

**背景**：
- 审批版 §7.4 灰度流程：新建 rule_version（rules_json 快照 + gray_ratio=10）→ 按 userId%100 采样到新版本 → 监控误判率 → 异常 gray_ratio=0 秒回滚 → 稳定 3 天全量。
- §5.2「阈值可配」已落地（VerifyProperties + Nacos）；但灰度采样、版本快照、回滚都未做。
- `rule_version` 表（含 gray_ratio、status GRAY/ACTIVE/RETIRED、rules_json）已在 `sql/03-verify-db.sql` 建成。
- §7.3 缓存一致性（Caffeine+Redis + Nacos 失效）已部分到位（CacheConfig 有 TTL），可复用其刷新链路。

**当前状态**：阈值从 `application.yml`/Nacos `verify.rules.*` 读，改动对全体用户立即生效，无版本化、无采样、无回滚；`rule_version` 表空置。

**期望状态**：管理员可创建规则版本（snapshot 规则+阈值）→ 设 gray_ratio 采样 → verify 按 userId%100 路由到灰/基线版本 → 异常设 gray_ratio=0 秒回滚 → 稳定后全量并 RETIRED 旧版本。

## What Changes

- **rule_version 读写**：verify-service 新增 `RuleVersion` 实体 + `RuleVersionMapper`，支持创建/更新灰度比例/标记 ACTIVE/GRAY/RETIRED。
- **灰度采样路由**：校验入口按 `userId % 100 < gray_ratio` 决定使用「灰度规则快照」或「基线规则」；缓存按版本号区分 key（`rules:v{version}`），避免版本间混淆。
- **规则快照**：新建版本时把当前 Nacos `verify.rules.*` 序列化为 `rules_json` 存库；灰度期间读快照执行，不依赖 Nacos 动态刷新（消除「灰度观察期配置又被改」的竞态）。
- **秒级回滚**：`gray_ratio=0` 立即生效（短 TTL 缓存 + 失效监听），新版本不再被采样，基线不受影响。
- **全量发布**：稳定后 `gray_ratio=100` + 旧版本置 RETIRED；新版本变为基线。
- **管理端接口（Swagger 级）**：`POST /verify/rules/versions`（创建）、`PATCH /verify/rules/versions/{id}/gray`（调灰度）、`POST /verify/rules/versions/{id}/activate`（全量）。
- **Caffeine 缓存**：版本快照缓存（短 TTL 或事件失效），命中即不查库。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加规则灰度能力域需求（ADDED）：版本化、采样路由、秒级回滚、全量发布。

### 受影响的代码
- `verify-service`：+`RuleVersion`、`RuleVersionMapper`、灰度路由逻辑、版本接口
- `api`：+`VerifyApi` 增补版本管理契约（或内部接口）
- `sql`：`rule_version` 表已建，无需改

### 用户影响
- 校验行为可在「分用户灰度」下改变，最终用户无感知；管理员获得可控发布能力。

### API 变更
- 新增管理端点：创建规则版本 / 调灰度比例 / 全量发布（见 What Changes）。
- 无破坏性变更。

### 需要迁移
- [x] 数据库迁移（rule_version 表已建，无改表）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（速览手册 §13 灰度行、README、ADR）

## 时间线评估

中等：约 1 周（P1 尽量，对应「阈值配置/灰度」里程碑）。

## 风险

- **灰度与 Nacos 动态刷新竞态** → 缓解：灰度期间用库内 `rules_json` 快照执行，不与 Nacos 实时值混淆；全量发布后才回切 Nacos。
- **采样命中判定不一致**（同用户两次请求跨版本）→ 缓解：采样键 user_id%100 稳定不变，同用户始终同分支，消除抖动。
- **回滚延迟** → 缓解：灰度比例走短 TTL 缓存（≤60s）+ 版本失效监听，异常时 gray_ratio=0 秒级生效。
- **rule_version 表脏数据** → 缓解：同一时刻至多一个 GRAY/ACTIVE 版本（唯一索引或状态迁移约束），旧版本 RETIRED。
- **技术卡壳超时**（既定决策）→ 缓解：灰度属 P1，可与监控/第5服务并行；若抢主线，核心「采样+回滚」优先，全量发布流程可简化。