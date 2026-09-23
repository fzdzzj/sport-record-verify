# 规格 Delta：add-strict-secret-fail-fast

## ADDED Requirements

### Requirement: 密钥注入严格模式

WHEN 配置 `app.security.strict=true`,
系统 SHALL 在启动期校验全部安全密钥项（JWT 签名密钥、内部接口共享密钥的收发两侧）：
任一密钥项未显式注入（解析结果为 null 或等于本地演示默认串）时 SHALL 启动失败（fail-fast，
抛 `IllegalStateException`），SHALL NOT 回落到硬编码演示默认值静默运行。

#### Scenario: strict 模式下密钥缺失启动失败

GIVEN `app.security.strict=true`
WHEN 任一密钥项未注入（配置缺失，解析回落到演示默认串）
THEN 应用上下文启动失败（`IllegalStateException`）
AND 失败信息指明缺失的密钥配置项

#### Scenario: strict 模式下密钥显式注入正常启动

GIVEN `app.security.strict=true`
WHEN 全部密钥项均已显式注入
THEN 应用上下文正常启动
AND 各密钥行为与默认模式一致

#### Scenario: 默认模式零扰动

GIVEN 未配置 `app.security.strict`（默认 false）
WHEN 以本地演示默认值启动
THEN 行为与引入本开关前逐位一致
AND 演示默认值原样保留

### Requirement: 内部接口密钥常量时间比较

WHEN `InternalApiAuthFilter` 校验 `X-Internal-Token` 与配置密钥是否一致,
系统 SHALL 使用常量时间比较（`MessageDigest.isEqual`）判定，
SHALL NOT 使用 `String.equals` 等非常量时间比较；判定结果 SHALL 与等值比较语义等价
（一致放行，不一致 403/1002）。

#### Scenario: 常量时间比较行为等价

GIVEN 内部接口密钥校验开启
WHEN 请求携带与配置一致的 token
THEN 放行（与既有行为一致）
WHEN 请求携带不一致或缺失的 token
THEN 拒绝（403/1002，与既有行为一致）
