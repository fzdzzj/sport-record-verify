# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（Web 业务控制台）。

## ADDED Requirements

### Requirement: Web 业务控制台

WHEN 已登录用户在 Web 控制台提交内置或粘贴的轨迹样例,
系统 SHALL 调用既有记录提交接口，并允许查询判定结果、对通过记录点赞、管理好友、查看总榜与好友榜。
系统 SHALL NOT 在本变更中新增「我的记录」后端列表接口。

#### Scenario: 提交样例记录

GIVEN 用户已登录且鉴权开启
WHEN 使用内置样例点提交记录
THEN 返回 recordId
AND 页面展示当前状态

#### Scenario: 判定中轮询

GIVEN 记录已提交且判定尚未结束
WHEN 查询判定结果
THEN 展示校验中
AND 自动再次查询直到终态或达到重试上限

#### Scenario: 拒绝后可申诉

GIVEN 判定为拒绝
WHEN 用户提交申诉原因
THEN 调用既有申诉接口
AND 展示申诉结果或错误信息

#### Scenario: 未通过不可点赞

GIVEN 记录未通过校验
WHEN 用户点赞
THEN 页面展示业务错误
AND 不把该错误显示为成功

#### Scenario: 总榜可查询

GIVEN 用户打开榜单页
WHEN 选择总榜
THEN 展示排名、昵称与里程
AND 空榜时展示空状态而非报错

#### Scenario: 会话内记住提交的记录

GIVEN 本次浏览器会话已成功提交记录
WHEN 用户进入判定页未手工输入 id
THEN 可从本机会话记录中选择最近提交的 recordId
AND 文档说明刷新或更换浏览器后不会从服务端恢复列表
