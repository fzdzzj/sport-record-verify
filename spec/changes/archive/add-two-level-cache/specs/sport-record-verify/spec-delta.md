# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（二级缓存能力域，全部为新增）。

## ADDED Requirements

### Requirement: 二级缓存读路径
WHEN 读取规则或灰度路由,
系统 SHALL 按「Caffeine 本地 → Redis → DB → 回填两级」顺序读取，缓存缺失时 SHALL 查库并回填两级。

#### Scenario: 本地命中
GIVEN 规则快照在 Caffeine 中且未过期
WHEN 读取规则
THEN 直接返回本地值
AND 不访问 Redis 与 DB

#### Scenario: 本地未命中 Redis 命中
GIVEN Caffeine 未命中但 Redis 有值
WHEN 读取规则
THEN 返回 Redis 值
AND 回填 Caffeine

#### Scenario: 两级未命中回源
GIVEN Caffeine 与 Redis 均无
WHEN 读取规则
THEN 查库（Nacos 配置落库/rule_version）
AND 回填 Redis 与 Caffeine 两级

### Requirement: 空值缓存防穿透
WHEN 查询不存在的规则或版本,
系统 SHALL 缓存空值哨兵（短 TTL），避免重复穿透到 DB。

#### Scenario: 空值缓存
GIVEN 查询的灰度版本号在 DB 不存在
WHEN 首次查询
THEN 缓存空值哨兵（短 TTL）
AND 后续相同查询不再打库

### Requirement: 互斥重建防击穿
WHEN 缓存失效且多实例并发重建同一 key,
系统 SHALL 用 Redisson 锁保证仅一个实例查库重建，其余 SHALL 等待或短退避。

#### Scenario: 单实例重建
GIVEN 某规则缓存已失效
AND 多个服务实例并发请求该规则
WHEN 触发重建
THEN 仅一个实例持有 lock:rule-rebuild:{key} 查库重建
AND 其余等待或复用重建结果

### Requirement: 随机 TTL 防雪崩
WHEN 设置缓存过期时间,
系统 SHALL 在基础 TTL 上叠加随机抖动，避免批量同时过期打库。

#### Scenario: TTL 抖动
GIVEN 基础缓存 TTL 60s
WHEN 写入缓存
THEN 实际 TTL 在 60s ± 随机抖动范围内
AND 不出现大批量同刻过期

### Requirement: Nacos 变更精准失效
WHEN 规则配置或版本变更,
系统 SHALL 精准失效对应 Caffeine 与 Redis 缓存（而非仅靠 TTL 兜底），并 SHALL 保留 TTL 兜底。

#### Scenario: 变更即失效
GIVEN 规则灰度比例或版本变更
WHEN 变更监听触发
THEN invalidate 对应 Caffeine key
AND DEL 对应 Redis key
AND 下次读取回源到最新值

---

## 备注

- 本变更落地审批版 §7.3 弹药 I，把「二级缓存 + 三防护」由讲设计升级为实锤。
- 二级缓存仅覆盖「规则快照/灰度路由」低频变更读路径；「判定结果」缓存维持单层 Caffeine（有状态机幂等兜底，无需二级）。
- 复用项目已接入的 Redisson（互斥重建锁）+ 现有 Caffeine 实例 + Nacos 配置监听。