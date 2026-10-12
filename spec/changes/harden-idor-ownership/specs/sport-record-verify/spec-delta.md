# spec-delta：id 类端点归属校验补全（TASK-204 harden-idor-ownership）

## MODIFIED 需求：记录域轨迹分页的调用者归属校验（record-service）

`RecordController.points` SHALL 在 `app.auth.enabled=true` 时以网关注入的 X-User-Id 认定调用者（缺头→401/1001）并将调用者下传；`SportRecordService.pagePoints` SHALL 在记录存在性校验后断言 `caller.equals(record.userId)`，不一致 → 403/1002（越权）。`app.auth.enabled=false` 时 SHALL 保持旧行为（caller 为空，不做归属断言）。服务间 Feign 读路径 `listPoints` SHALL 保持不变。

## MODIFIED 需求：记录域申诉的调用者归属校验（record-service）

`RecordController.appeal` SHALL 在 `app.auth.enabled=true` 时以下传调用者触发归属断言；`SportRecordService.appeal` SHALL 断言调用者等于 `record.userId`，不一致 → 403/1002。建申诉单的归属人 SHALL 仍以 `record.getUserId()` 为服务端权威（不信任调用方入参）。`app.auth.enabled=false` 时 SHALL 保持旧行为。

## MODIFIED 需求：好友申请同意/拒绝的调用者归属校验（user-service）

`FriendController.accept/reject` SHALL 在 `app.auth.enabled=true` 时以 X-User-Id 认定调用者并下传；`FriendService.accept/reject` SHALL 在申请存在性校验后断言 `caller.equals(request.toUser)`，不一致 → 403/1002（含天然阻断 from_user 自同意）。内部 Feign 路径 `InternalFriendController` SHALL 显式传入空调用者（/internal/** 网内信任，不做归属断言）。`app.auth.enabled=false` 时 SHALL 保持旧行为。

## 验收断言

红：R1/R4 调用者≠记录归属人→403/1002；R2/R3 调用者≠申请 to_user→403/1002。绿：调用者==归属人正常流转；调用者为空时降级放行（旧行为）。offline 638 基线两模块只增不减、其余五模块逐位不变；token 2030 持平；零网关/common/api 契约/config/compose/脚本改动。