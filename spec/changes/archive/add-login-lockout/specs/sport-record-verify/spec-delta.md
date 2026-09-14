# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（账号锁定能力域，全部为新增）。

## ADDED Requirements

### Requirement: 登录失败锁定
WHEN 同一手机号在窗口期内连续登录失败达阈值,
系统 SHALL 锁定该账号，锁定期间 SHALL 拒绝登录且不校验密码，锁定期满 SHALL 自动解锁。

#### Scenario: 达阈值触发锁定
GIVEN 手机号在 15 分钟窗口内已失败 5 次
WHEN 再次登录（第 6 次）
THEN 系统拒绝登录
AND 不校验密码（直接返回锁定）
AND 锁定开始计时

#### Scenario: 锁定期间拒绝
GIVEN 账号处于锁定状态
WHEN 用户提交任意密码登录
THEN 返回「账号已临时锁定」
AND 不消耗 BCrypt 校验
AND 不更新失败计数

#### Scenario: 锁定期满自动解锁
GIVEN 账号锁定已达到锁定时长
WHEN 用户登录
THEN 锁定键过期（TTL 到期）
AND 恢复校验密码

### Requirement: 登录成功清零
WHEN 用户登录成功,
系统 SHALL 清除该手机号的失败计数与锁定状态，防止「试错后纠正」导致的计数残留。

#### Scenario: 成功清零
GIVEN 手机号有若干失败计数（未达阈值）
WHEN 该用户登录成功
THEN 失败计数清零
AND 若存在锁定键则一并清除

### Requirement: 防爆破与降级
WHEN 系统实施锁定,
系统 SHALL 不区分「用户不存在」与「密码错误」（统一返回，防撞库探测），且 Redis 不可用时 SHALL 降级为「继续计数告警但不阻断登录」。

#### Scenario: 防撞库探测
GIVEN 登录失败
WHEN 返回错误
THEN 用户不存在与密码错误返回同一语义
AND 不泄露账号是否存在

#### Scenario: Redis 不可用降级
GIVEN Redis 不可用
WHEN 执行登录失败计数与锁定
THEN 降级为仅告警不阻断
AND 登录流程不因 Redis 故障失败

---

## 备注

- 本变更是 add-jwt-auth 的安全闭环收尾：把 AuthService 已实现的「失败计数 + 告警」升级为「计数 + 真正锁定 + 自动解锁」。
- 锁定状态复用 Redis（auth:lock:{phone} + TTL），不落库不改表。
- 阈值/窗口/锁定时长全部配置化（app.auth.lock.*），默认 5 次 / 15min 窗口 / 15min 锁定，对齐现有 FAIL_THRESHOLD/FAIL_COOLDOWN_MINUTES 常量。