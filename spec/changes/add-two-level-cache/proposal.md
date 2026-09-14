# 提案：落地 Caffeine + Redis 二级缓存（穿透/击穿/雪崩三防护）

## Why

审批版 §7.3（弹药 I）设计了「Caffeine → Redis → DB（Nacos 规则落库）→ 回填两级」的二级缓存 + 三防护（穿透=空值缓存、击穿=互斥重建、雪崩=随机 TTL），但当前 verify-service 只有**单层 Caffeine 本地缓存**（`CacheConfig` 1min TTL，承载判定结果/灰度路由/版本快照）。二级缓存的「Redis 中间层 + 互斥重建 + 空值缓存 + 随机 TTL + Nacos 失败通知」全是「讲设计」，从未落地。

这是当前缓存相关最大的一块「讲设计但没写代码」的技术债，也是面试追问「缓存一致性」「缓存穿透/击穿/雪崩」时，最经得起追问的实锤。

**背景**：
- 审批版 §7.3 完整定义了二级缓存读路径与失效策略（Nacos 配置变更 → 监听 → Caffeine invalidate + Redis DEL + TTL 兜底 60s）。
- verify-service `CacheConfig` 已有 Caffeine 实例（1min TTL，capacity 1 万，recordStats 已开），灰度路由/版本快照/判定结果三种 key 已在用。
- 规则快照由 `RuleVersionService` 读库（`rule_version.rules_json`），当前每次灰度路由可能直接查库，无 Redis 中间层。
- 「互斥重建」可复用项目已接入的 Redisson 分布式锁。

**当前状态**：Caffeine 单层缓存已实现且被 VerifyService 用于缓存判定结果；无 Redis 二级层、无空值缓存、无互斥重建、无随机 TTL、无 Nacos 变更失效监听（现有 1min TTL 是「回滚 ≤60s」的粗糙上界）。

**期望状态**：规则读取走「Caffeine → Redis → DB 回填两级」；热点规则命中 Redis 不必每次查库；空值缓存防穿透；互斥重建防击穿（多实例并发查同一规则只允许一个重建）；随机 TTL 防雪崩；Nacos 变更时精准失效 Caffeine + Redis。

## What Changes

- **二级缓存抽象**：抽出 `RuleCacheService`（或两段式 `CachedRuleLoader`），封装「本地 Caffeine → Redis → DB 回填」读路径，统一供 `RuleVersionService` 读取规则快照/灰度路由使用。
- **空值缓存防穿透**：DB 查不到（如灰度版本号不存在）时缓存空值哨兵（短 TTL），避免恶意/异常请求穿透打库。
- **互斥重建防击穿**：缓存失效且并发重建同一 key 时，用 Redisson 锁 `lock:rule-rebuild:{key}` 保证仅一个实例查库重建，其余等待或短退避（复用项目已有 Redisson）。
- **随机 TTL 防雪崩**：Redis 层 TTL = 基础值 + 随机抖动（如 60s ± 10s），避免批量同时过期打库。
- **Nacos 精准失效**：规则配置/版本变更时，监听回调里 `Caffeine.invalidate(key)` + `Redis DEL`，而非只靠 TTL 兜底；保留 TTL 兜底（60s）。
- **错位明确**：二级缓存只覆盖「规则快照/灰度路由」读路径（读多写少、变更低频），不覆盖「判定结果」缓存（判定结果继续走单层 Caffeine，因其有状态机幂等兜底，无需二级）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加二级缓存能力域需求（ADDED）：两级读路径、三防护、精准失效。

### 受影响的代码
- `verify-service`：+`RuleCacheService`（或等价）、改 `RuleVersionService` 走二级读路径、`CacheConfig` 增 Redis 模板/锁注入、Nacos 失效监听
- `application.yml`：缓存 TTL/随机抖动/开关配置
- `common`：无（复用 ResultCode）

### 用户影响
- 规则读取性能提升（热点规则命中 Redis/Caffeine，减少查库）；缓存一致性更精准（变更秒级生效而非等 60s）。

### API 变更
- 无新增业务端点；规则读取内部路径变化。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（ADR 补二级缓存设计、README/速览弹药 I 标注「已实现」）

## 时间线评估

中等：约 0.5-1 周（聚焦 verify-service 读路径）。

## 风险

- **二级缓存与灰度精度的竞态**（灰度比例变更后 Redis 旧值未失效）→ 缓解：灰度比例读路径走短 TTL + 变更精准失效；判定结果不受影响（结果缓存独立）。
- **互斥重建引入锁等待延迟** → 缓解：重建锁等待设短超时（如 3s），拿不到锁返回旧值或空准备重试，不阻塞校验主链路。
- **Redis 抖动导致缓存全 miss 打库** → 缓解：Caffeine 本地层兜底，Redis 不可用时降级为「只走 Caffeine + DB」不报错。
- **过度缓存误伤一致性** → 缓解：只缓存「规则快照/灰度路由」低频变更数据，判定结果维持单层 Caffeine（有双重幂等兜底），避免把二级缓存滥用到高频写场景。