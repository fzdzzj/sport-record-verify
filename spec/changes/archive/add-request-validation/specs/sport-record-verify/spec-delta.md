# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（入参校验与 HTTP 错误契约，「工程结构 / 统一响应与错误码」域内修改）。

> 补记说明：本变更的规范修改在落地提交时已直接并入 `spec.md`（「统一响应与错误码」需求新增「入参校验失败」「路径与方法契约」两个 Scenario，并扩充 WHEN/SHALL 语义），但变更目录当时漏建 `spec-delta.md`。本文件按已并入的实际文本回填，保证归档目录自洽。归档时无需再次合并。

## MODIFIED Requirements

### Requirement: 统一响应与错误码

原需求只约定「`Result<T>` 包装 + 业务异常结构化 + 不泄漏堆栈」，未约定**调用方入参问题**的 HTTP 语义；实现上 DTO 无校验注解、`MethodArgumentNotValidException` 分支是死代码，畸形 JSON / 参数类型不匹配 / 缺必填查询参数一律落入 `Exception` 兜底返回 500 —— 调用方错误被呈现为服务端故障。

WHEN 任一服务返回结果,
系统 SHALL 使用统一 `Result<T>` 包装，失败时 SHALL 返回结构化错误码与信息；SHALL 区分「调用方入参错误（HTTP 400/404/405）」与「服务端故障（HTTP 500 + 9999）」，且入参错误 SHALL NOT 落入 500。

#### Scenario: 入参校验失败

GIVEN 客户端提交非法入参（DTO 校验违规 / 畸形 JSON / 参数类型不匹配 / 缺必填查询参数）
WHEN 控制器层触发校验
THEN 返回 HTTP 400 与结构化错误体 `{code:400, message:<字段名 + 提示>}`
AND 不落入服务端 500

#### Scenario: 路径与方法契约

GIVEN 请求路径存在但方法不符，或路径不存在
WHEN 全局异常处理器拦截
THEN 分别返回 HTTP 405 与 404 及结构化错误体

---

## 备注

- 校验注解落在 `api` 模块 DTO（声明式契约随契约模块一起发布）；`api` 引 `jakarta.validation-api`，四个服务模块引 `spring-boot-starter-validation` 提供实现（hibernate-validator）。
- 边界口径：轨迹点列表上限 20000（与提交契约一致）；sportType ∈ [1,3]（与 `SportType` 对齐）；规则灰度比例 ∈ [0,100]。
- service 层只删**与注解语义完全重复**的判空，业务规则判空（状态机、幂等、枚举语义）保留 —— 注解管格式，service 管业务。
- 404 契约依赖 `spring.mvc.throw-exception-if-no-handler-found`；未开启时容器默认 404，处理器保留使开启后即刻生效，无回归。
- 错误码总表沉淀至 `docs/错误码表.md`（BizException 全量码 + HTTP 映射 + 触发场景）。
