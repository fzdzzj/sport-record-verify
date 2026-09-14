# 提案：后端入参校验与 HTTP 错误契约

## Why

全项目 `@RequestBody` 28 处、DTO 25 个，但校验注解（`@NotBlank/@Size/@Pattern` 等）**0 个**，`@Valid` 仅 1 处且出现在 GlobalExceptionHandler 自身。入参判空全靠在 service 层手写（AuthService:96、SportRecordService:69、RecordController:103 等各写一遍），且校验失败语义不一致。

GlobalExceptionHandler 已实现 `MethodArgumentNotValidException` 处理，但无人使用 `@Valid`，该分支是死代码。畸形 JSON（`HttpMessageNotReadableException`）、参数类型不匹配（`MethodArgumentTypeMismatchException`）、缺必填查询参数（`MissingServletRequestParameterException`）会落入 `Exception` 兜底返回 500——调用方入参问题错误地呈现为服务端故障。

**当前状态**：DTO 无校验注解；畸形 JSON / 类型不匹配 / 缺参返回 500；service 层重复手写判空。
**期望状态**：DTO 声明式校验注解 + 控制器 `@Valid` 触发，统一 400 + 结构化错误体；service 层只保留业务规则判空。

## What Changes

- **api 模块 DTO**：auth（Login/Register/Refresh/AdminGrant）、record（RecordSubmit/TrackPoint/StatusCallback/LikeRequest）、verify（AppealCreate/AppealReview）、user（FriendRequestCreate）、mapmatch（MapMatchRequest/Point）等 DTO 补 `jakarta.validation` 注解：
  - phone：`@NotBlank` + `@Pattern(^1[3-9]\d{9}$)`；password：`@NotBlank` + `@Size(6,64)`
  - requestId：`@NotBlank` + `@Size(≤64)`；sportType：`@NotNull` + `@Min(1)` + `@Max(3)`（与 SportType 对齐）
  - 轨迹点列表：`@NotEmpty` + `@Size(≤20000)` + 级联 `@Valid`；轨迹点内 seq/lat/lng/ts 必填 + 范围（lat∈[-90,90]、lng∈[-180,180]）
  - 其余字段按语义补 `@NotNull/@Size/@Min/@Max/@DecimalMin/@DecimalMax`
- **verify-service DTO**：RuleVersionCreateRequest / RuleVersionGrayRequest 补比例 `@Min(0)/@Max(100)` 等
- **控制器**：写接口 `@RequestBody` 统一加 `@Valid`（19 处）；查询接口保持按需
- **依赖**：api 模块加 `jakarta.validation-api`；4 个服务模块加 `spring-boot-starter-validation`（hibernate-validator 实现）
- **GlobalExceptionHandler**：补 `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` / `MissingServletRequestParameterException` → 400；`NoHandlerFoundException` → 404、`HttpRequestMethodNotSupportedException` → 405（评估结论：404/405 一并补齐，契约完整）
- **service 层**：去掉与 DTO 注解重复的判空（保留业务规则校验，如状态机、幂等、枚举语义）

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 「统一响应与错误码」下补充 HTTP 契约异常映射（MODIFIED）。

### 受影响的代码
- `api`：dto/**（15 个文件）、pom.xml
- `common`：GlobalExceptionHandler、pom.xml
- `user-service / record-service / verify-service / mapmatch-service`：controller/**、部分 service（去重判空）、pom.xml
- 新增：`docs/错误码表.md`

### 用户影响
- 非法入参（超长 phone、非法 sportType、畸形 JSON、缺参）由 500 变为 400 + 结构化错误体，客户端可明确区分「调用方错误」与「服务端故障」。

### API 变更
- 无接口路径/字段变更；仅新增入参校验行为（400 契约）。

### 需要迁移
- [ ] 数据库迁移（无）
- [ ] API 版本提升（无）
- [ ] 用户沟通（无）
- [x] 文档更新（docs/错误码表.md）

## 时间线评估

小。改动集中在 DTO 注解、控制器 @Valid 与异常处理器新增分支，service 层仅删重复判空。

## 风险

- **校验误伤合法入参** → 缓解：注解与既有业务判空口径逐字段核对；轨迹点上限 20000 与提交契约一致。
- **Service 层遗漏去重** → 缓解：仅删除与注解语义完全重复的判空，保留业务级规则（状态机/幂等/枚举）。
- **404 契约依赖开关** → 缓解：`throw-exception-if-no-handler-found` 未开启时容器默认 404，处理器保留使开启后即刻生效，无回归。
