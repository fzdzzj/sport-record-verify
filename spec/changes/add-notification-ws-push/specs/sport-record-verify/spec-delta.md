# spec-delta：add-notification-ws-push

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（通知实时推送：WebSocket+STOMP 管道，判定事件落库后秒级触达在线用户，60s 轮询降级为兜底）。

## ADDED Requirements

### Requirement: 通知实时推送

WHEN 一条通知成功落库,
系统 SHALL 通过 WebSocket（STOMP 子协议）长连接将该通知实时推送给其收件人的在线会话（用户目标队列），推送 SHALL 经跨实例扇出通道（Redis 发布订阅）广播至全部服务实例、由持有该收件人会话的实例投递, 系统 SHALL NOT 对同一收件人产生重复投递。WHEN 客户端建立推送连接, 系统 SHALL 在 STOMP CONNECT 帧级校验访问令牌并以令牌身份建立会话, SHALL NOT 接受未携带有效令牌的连接。WHEN 会话建立, 系统 SHALL 以 STOMP 协议心跳保活, 并在会话终止时自动注销其订阅。WHEN 推送通道中断, 客户端 SHALL 以内建重连恢复，且轮询兜底 SHALL 保持通知新鲜度不劣于纯轮询基线。系统 SHALL NOT 在通知落库失败时发布推送。

#### Scenario: 落库成功触发实时推送

GIVEN 一条通知已成功落库
WHEN 推送发布点执行
THEN 该通知经扇出通道广播至全部实例
AND 持有该收件人会话的实例向其用户目标队列投递通知消息

#### Scenario: 有效令牌建立会话

GIVEN 客户端在 STOMP CONNECT 帧携带有效访问令牌
WHEN 建立推送连接
THEN 会话以令牌解析出的用户身份建立
AND 该用户此后收到指向其用户目标队列的推送

#### Scenario: 无效或缺失令牌拒绝连接

GIVEN 客户端在 STOMP CONNECT 帧携带的令牌无效、过期或缺失
WHEN 建立推送连接
THEN 连接被拒绝
AND 不建立任何会话

#### Scenario: 会话终止自动注销订阅

GIVEN 一个已建立的推送会话
WHEN 客户端断开或连接关闭
THEN 该会话的订阅自动注销
AND 后续推送不再向其投递

#### Scenario: STOMP 心跳保活

GIVEN 一个活跃的推送会话
WHEN 心跳协商周期到达
THEN 双方按协商周期交换心跳帧
AND 链路上的连接因周期流量不被空闲回收

#### Scenario: 断流降级不劣于轮询基线

GIVEN 客户端推送连接中断且重连未恢复
WHEN 轮询兜底周期到达
THEN 未读状态仍以轮询周期刷新
AND 通知新鲜度不劣于纯 60 秒轮询基线

#### Scenario: 落库失败不发布

GIVEN 一条通知落库失败（受影响行数为 0）
WHEN 推送发布点执行
THEN 不向扇出通道发布任何消息
AND 在线会话不收到该失败通知的推送

#### Scenario: 未登录不开流

GIVEN 前端处于未登录态（无访问令牌）
WHEN 通知组件初始化
THEN 不建立推送连接、不发起未读请求
AND 角标置 0

#### Scenario: 跨实例不重复投递

GIVEN 同一收件人的会话建立在其中一个服务实例上
WHEN 一条面向该收件人的通知经扇出通道广播
THEN 仅持有其会话的实例投递该通知
AND 其他实例不产生第二次投递
