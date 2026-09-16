# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（存量库迁移入口 + schema 漂移可观测）。

## ADDED Requirements

### Requirement: 存量库迁移入口

WHEN 开发者对已初始化的 MySQL 数据卷执行存量迁移入口,
系统 SHALL 按确定顺序幂等执行 `sql/migrations` 下的全部脚本，使存量库结构与当前代码所需 schema 对齐；SHALL NOT 依赖 `docker-entrypoint-initdb.d` 在非首次启动时补齐列或索引；SHALL NOT 把首次建表脚本当作存量升级脚本执行。

#### Scenario: 缺列的存量库被补齐

GIVEN MySQL 数据卷已初始化
AND `user_db.user` 缺少当前代码所需的 `role` 列
WHEN 执行存量迁移入口
THEN `user.role` 列存在
AND 脚本以成功状态退出

#### Scenario: 已对齐的库幂等重跑

GIVEN 存量库结构已与当前代码对齐
WHEN 再次执行存量迁移入口
THEN 脚本成功退出
AND 不报错
AND 不破坏既有数据与结构

#### Scenario: 中间件未就绪则失败

GIVEN MySQL 容器不存在或不健康
WHEN 执行存量迁移入口
THEN 脚本以非 0 状态退出
AND 不假装迁移成功

#### Scenario: 不执行首次建表脚本

GIVEN 存量迁移入口被调用
WHEN 扫描待执行脚本
THEN 仅执行 `sql/migrations` 下的脚本
AND 不执行 `sql/01-user-db.sql` 等首次建表脚本

### Requirement: 登录路径 schema 漂移可观测

WHEN 执行冒烟前置检查,
系统 SHALL 以错误凭据调用登录接口，并按响应区分 schema 漂移、凭据错误、账号锁定与服务未就绪；SHALL NOT 把锁定或网关失败当成缺列，也 SHALL NOT 把 HTTP 500 或业务码 9999 当成通过。

#### Scenario: schema 可用时凭据错误返回 401 与 1001

GIVEN 网关与 user-service 已启动
AND `user` 表含当前代码所需列
AND 探测账号未被锁定
WHEN 使用错误密码调用登录接口
THEN HTTP 状态为 401
AND 业务码为 1001
AND 不返回 9999
AND 不返回 HTTP 500

#### Scenario: 缺 role 列时冒烟失败

GIVEN 存量库 `user` 表缺少 `role` 列
AND 探测账号未被锁定
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败信号为 HTTP 500 或业务码 9999

#### Scenario: 服务未就绪不判为缺列

GIVEN 网关或 user-service 不可达
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败原因指向服务未就绪（连接失败或 HTTP 502/503）
AND 不指向 schema 漂移

#### Scenario: 账号锁定不判为缺列也不判为通过

GIVEN 探测手机号处于登录锁定
WHEN 执行冒烟前置检查
THEN 检查判定失败
AND 失败原因指向账号锁定（HTTP 403 或业务码 1002）
AND 不指向 schema 漂移
AND 不视为 schema 健康

## MODIFIED Requirements

### Requirement: 数据库初始化

WHEN 首次启动各服务,
系统 SHALL 执行审批版 §6 建表脚本，初始化 user_db / record_db / verify_db 中的全部表。
首次初始化之后的结构变更 SHALL 走存量迁移入口，SHALL NOT 假定再次启动容器会自动补列。

#### Scenario: 首次建表成功

GIVEN 三个库为空
WHEN 执行建表脚本
THEN 全部表创建成功
AND 字段与审批版 §6 定义一致

#### Scenario: 幂等重放

GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

#### Scenario: 已有数据卷不自动补列

GIVEN MySQL 数据卷已经完成过首次初始化
WHEN 再次执行 `docker compose up`
THEN 不会再次执行 `docker-entrypoint-initdb.d` 中的建表脚本
AND 后续结构变更须经存量迁移入口执行

---

## 备注

- 新装库继续只靠 `sql/0*.sql`；存量库必须显式跑迁移入口。
- `sql/migrations/add-idx-record-seq.sql` 仍不进入首次 initdb，以免破坏无索引压测基线；它属于存量升级默认集合。
- 本变更不引入 Flyway/Liquibase/Testcontainers，也不把迁移挂到每次 compose up。
- 公开 markdown 不要写入口径禁用词列表；自检使用 CI 已有 step。
