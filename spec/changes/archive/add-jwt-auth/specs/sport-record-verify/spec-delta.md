# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（JWT 鉴权能力域，全部为新增）。

## ADDED Requirements

### Requirement: 用户注册
WHEN 用户提交注册,
系统 SHALL 校验手机号唯一并 SHALL 使用 BCrypt 哈希存储密码，重复手机号 SHALL 返回 2001。

#### Scenario: 注册成功
GIVEN 用户提交新手机号与密码
WHEN 注册接口处理
THEN 创建用户（密码 BCrypt 哈希）
AND 返回注册成功

#### Scenario: 手机号重复
GIVEN 手机号已存在注册用户
WHEN 再次注册同一手机号
THEN 返回 2001（手机号已注册）
AND 不创建新用户

### Requirement: 用户登录
WHEN 用户提交凭据,
系统 SHALL 校验密码，成功 SHALL 签发 access token 与 refresh token，失败 SHALL 返回 401 并计失败次数。

#### Scenario: 登录成功
GIVEN 手机号与密码正确
WHEN 登录接口处理
THEN 返回 access token 与 refresh token
AND access 短时效、refresh 长时效

#### Scenario: 密码错误
GIVEN 密码错误
WHEN 登录接口处理
THEN 返回 401
AND 计失败次数（超过阈值锁定的设计口径）

### Requirement: Token 刷新与轮换
WHEN access token 过期,
系统 SHALL 凭 refresh token 刷新，且 SHALL 轮换（旧 refresh 作废，新 refresh 下发）。

#### Scenario: 刷新成功
GIVEN refresh token 有效且未作废
WHEN 调用刷新接口
THEN 返回新 access 与新 refresh
AND 旧 refresh 作废

#### Scenario: refresh 已作废
GIVEN refresh token 已轮换过或过期
WHEN 调用刷新接口
THEN 返回 401（1001）

### Requirement: 网关统一鉴权
WHEN 请求进入网关,
系统 SHALL 校验 Authorization 头中的 token，失败 SHALL 返回 401（1001），成功 SHALL 解析 userId 并注入请求头透传下游。

#### Scenario: 有效 token 放行
GIVEN 请求携带有效 Bearer token
WHEN 网关过滤器处理
THEN 解析出 userId
AND 注入 X-User-Id 头透传下游

#### Scenario: 无效 token 拒绝
GIVEN 请求无 token 或 token 无效/过期
WHEN 网关过滤器处理
THEN 返回 401（1001）
AND 不放行至下游

#### Scenario: 白名单放行
GIVEN 请求路径属白名单（/api/auth、/internal、/actuator）
WHEN 网关过滤器处理
THEN 跳过鉴权直接放行

### Requirement: 用户数据隔离
WHEN 业务接口处理用户请求,
系统 SHALL 从网关注入的 userId 认定身份，而非信任调用方传入的 userId，越权访问他人资源 SHALL 返回 403（1002）。

#### Scenario: 正常访问本人数据
GIVEN 用户 A 携带自己的 token
WHEN 访问本人记录/点赞/好友
THEN 以 token 中 userId 为准处理
AND 返回正常结果

#### Scenario: 越权访问他人被拒
GIVEN 用户 A 尝试访问用户 B 的资源
WHEN 业务判定 userId 不一致
THEN 返回 403（1002）
AND 不泄露 B 的数据

### Requirement: 鉴权降级开关
WHEN 本地调试或压测需要,
系统 SHALL 提供 auth.enabled 开关，关闭时 SHALL 降级为显式携带 userId 的旧行为，默认关闭以降低迁移成本。

#### Scenario: 开关默认关闭
GIVEN auth.enabled=false（默认）
WHEN 请求受保护接口
THEN 沿用显式携带 userId 的旧行为
AND 不启用网关鉴权

#### Scenario: 开关启用
GIVEN auth.enabled=true
WHEN 请求受保护接口
THEN 强制走网关鉴权与数据隔离

---

## 备注

- 本提案收口跨多服务的「骨架无认证」技术债，锚点是全项目 6+ 处「接 JWT 后改从 token 解析」注释。
- refresh token rotation 复用副项目 life-habit-assistant 已验证经验；存储默认 Redis（TTL + 易作废）。
- 越权 403（1002）是核心价值：从「调用方自报家门」升级为「网关统一认定身份」。