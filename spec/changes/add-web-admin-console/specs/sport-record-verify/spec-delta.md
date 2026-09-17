# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（Web 治理面控制台）。

## ADDED Requirements

### Requirement: Web 治理面控制台

WHEN 角色为 ADMIN 的已登录用户打开治理面页面,
系统 SHALL 允许调用既有规则版本接口与申诉终判接口。
WHEN 普通用户访问同一页面或接口,
系统 SHALL 依赖网关返回 403，前端 SHALL NOT 绕过网关展示管理数据。

#### Scenario: 管理员可调灰度

GIVEN 用户持有 ADMIN 的有效 token
WHEN 调整某规则版本灰度比例
THEN 请求到达既有规则接口
AND 页面展示成功或既有业务错误码信息

#### Scenario: 普通用户被拒

GIVEN 用户角色为 USER
WHEN 访问治理面接口
THEN 展示无权限（403/1002）
AND 不展示可编辑的规则表单数据

#### Scenario: 终判需申诉编号

GIVEN 管理员打开终判页
WHEN 未提供后端申诉列表
THEN 页面提供申诉编号输入
AND 不把不存在的工单队列当成已交付能力

#### Scenario: 不暴露内部授予

GIVEN 阅读前端文档或页面
WHEN 查找授予管理员的入口
THEN 前端不提供 grant-admin 操作
AND 说明授予仍走内部凭证接口
