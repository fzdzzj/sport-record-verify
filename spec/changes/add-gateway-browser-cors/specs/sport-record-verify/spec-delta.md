# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（网关浏览器跨域）。

## ADDED Requirements

### Requirement: 网关浏览器跨域

WHEN 浏览器从已配置的前端来源直接访问网关,
系统 SHALL 对允许的来源响应 CORS 预检与跨域响应头，并允许 `Authorization` 请求头。
系统 SHALL NOT 在携带凭证的场景使用通配来源 `*`。
开发服务器反向代理 SHALL 仍可在不依赖本能力的情况下工作。

#### Scenario: 本地前端来源通过预检

GIVEN 请求 Origin 为本地 Vite 开发端口且该来源在允许列表中
WHEN 浏览器发出 OPTIONS 预检
THEN 网关允许该来源
AND 允许 Authorization 头

#### Scenario: 未允许的来源不按通配放行

GIVEN 请求 Origin 不在允许列表
WHEN 发出跨域请求
THEN 不按允许所有来源处理
AND 不把该拒绝当成鉴权失败码 1001

#### Scenario: 开发代理不强制依赖 CORS

GIVEN 前端开发服务器将请求反向代理到网关
WHEN 页面调用业务接口
THEN 请求可作为同源代理访问网关
AND 不要求必须先启用跨域才能本地开发
