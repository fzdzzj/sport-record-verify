# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（Web 控制台脚手架）。

## ADDED Requirements

### Requirement: Web 控制台工程脚手架

WHEN 开发者在仓库中启动 Web 控制台开发服务器,
系统 SHALL 提供独立的前端工程目录，使用 Vue 3 与 Vite，并将浏览器请求按网关既有前缀转发到本地网关，SHALL NOT 剥除 `/api` 或其他服务前缀。

#### Scenario: 开发服务器可启动

GIVEN 已安装 Node.js 与 pnpm
WHEN 在前端目录执行开发启动命令
THEN 开发服务器在本地端口监听
AND 不要求改动 Java 服务代码

#### Scenario: 代理保留网关前缀

GIVEN 开发代理已配置
WHEN 浏览器请求 `/leaderboard/api/leaderboard`
THEN 请求被转发到本地网关同一路径
AND 不被改写成去掉前缀的路径

#### Scenario: 统一响应按 code=0 解析

GIVEN 后端返回 JSON `{code, message, data}`
WHEN 前端请求封装处理响应
THEN `code=0` 视为成功
AND 非 0 向用户展示 `message`
AND 不把成功码当作 1
