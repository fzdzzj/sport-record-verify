# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（Web 登录会话）。

## ADDED Requirements

### Requirement: Web 控制台登录会话

WHEN 用户在 Web 控制台提交登录表单,
系统 SHALL 调用既有登录接口并保存 access 与 refresh；后续请求 SHALL 携带 `Authorization: Bearer` access token，SHALL NOT 由浏览器发送 `X-User-Id` 或 `X-Role` 作为身份。

#### Scenario: 登录成功进入控制台

GIVEN 用户提供正确手机号与密码
WHEN 提交登录
THEN 保存 accessToken 与 refreshToken
AND 进入需登录的控制台布局

#### Scenario: 凭据错误

GIVEN 密码错误且账号未锁定
WHEN 提交登录
THEN 停留在登录页
AND 展示凭据错误（对应 401/1001）
AND 不保存 token

#### Scenario: 账号锁定

GIVEN 账号处于登录锁定
WHEN 提交登录
THEN 展示锁定提示（对应 403/1002）
AND 不保存 token

#### Scenario: 未登录被拦

GIVEN 浏览器没有有效 access token
WHEN 访问需登录的控制台路径
THEN 跳转到登录页

#### Scenario: access 过期后刷新

GIVEN refresh token 仍有效而 access 已失效
WHEN 业务请求返回 401
THEN 使用 refresh 换取新双 token
AND 重试原请求一次
