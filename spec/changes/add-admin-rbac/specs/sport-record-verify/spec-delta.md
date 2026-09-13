# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（治理面鉴权能力域，全部为新增）。

## ADDED Requirements

### Requirement: 用户角色模型
WHEN 用户注册,
系统 SHALL 默认赋予 USER 角色，且 SHALL 提供内部接口授予 ADMIN 角色（最小权限，默认非管理员）。

#### Scenario: 注册默认 USER
GIVEN 新用户注册成功
WHEN 查询其角色
THEN 角色为 USER

#### Scenario: 内部授予 ADMIN
GIVEN 内部管理流程
WHEN 调用授予接口
THEN 指定用户角色变更为 ADMIN

### Requirement: 令牌携带角色
WHEN 登录签发令牌,
系统 SHALL 在 token 中写入 role claim，且 role SHALL 由签发端决定，SHALL 不被下游信任外部传入。

#### Scenario: token 含角色
GIVEN 用户登录成功
WHEN 解析 access token
THEN 可读取出 role claim 与 userId

### Requirement: 治理面鉴权
WHEN 请求访问管理端接口（/admin/** 或规则版本接口）,
系统 SHALL 要求 role=ADMIN，普通用户 SHALL 返回 403（1002），未登录 SHALL 返回 401（1001）。

#### Scenario: 管理员访问放行
GIVEN 请求携带 ADMIN 角色的有效 token
WHEN 访问管理端接口
THEN 放行至下游

#### Scenario: 普通用户被拒
GIVEN 请求携带 USER 角色的有效 token
WHEN 访问管理端接口
THEN 返回 403（1002）

#### Scenario: 未登录被拒
GIVEN 请求无有效 token
WHEN 访问管理端接口
THEN 返回 401（1001）

### Requirement: 白名单收紧
WHEN 网关过滤请求,
系统 SHALL 不为管理端接口提供匿名放行，且 SHALL 保持内部接口（/internal/**）网内信任边界。

#### Scenario: 管理端不匿名放行
GIVEN 请求路径为 /admin/**
WHEN 网关过滤
THEN 进入鉴权校验（不跳过）
AND 依角色判定放行或拒绝

#### Scenario: 内部接口维持网内
GIVEN 请求路径为 /internal/**
WHEN 网关过滤
THEN 维持网内信任（不对公网暴露）

---

## 备注

- 本变更补「业务面已鉴权、治理面裸奔」的不一致，是 JWT 鉴权闭环的治理面收口。
- 角色模型刻意最简（USER/ADMIN 二态），不用 Spring Security ACL，面试明确「最小权限 + 够用即可」。
- 角色与 userId 均由网关注入下游，不信任外部传入。