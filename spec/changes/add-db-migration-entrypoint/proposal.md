# 提案：统一存量库迁移入口（堵住 schema 漂移）

## Why

仓库已有两套 schema 来源，但只有一套会在升级路径上执行：

- `sql/01-*.sql` ~ `sql/04-*.sql` 经 `docker-entrypoint-initdb.d` **仅在数据卷首次初始化时**执行；
- `sql/migrations/` 已有 3 个幂等脚本（`add-user-role` / `add-sport-type-threshold` / `add-idx-record-seq`），**没有任何执行入口**——无脚本、README 未提、冒烟不断言。

这不是缺列定义，是缺「已有库如何升上来」。`add-admin-rbac` 把「数据库迁移」勾完成，实际只交付了 SQL 文件。

**代码侧已核对（不是只信交接文档）**：

- `AuthService.login` 在 Redis 锁定检查之后执行 `userMapper.selectOne(LambdaQueryWrapper<User>)`。`User` 实体含 `role`，MyBatis-Plus 默认查出全部字段。存量库缺 `role` 时，该 SELECT 会报 `Unknown column 'role'`。
- 未捕获的 SQL 异常走 `GlobalExceptionHandler.handleException` → **HTTP 500 + code 9999**。
- 凭据错误走 `AuthController` 本地 `BizException` 处理 → **HTTP 401 + code 1001**（`ResultCode.UNAUTHORIZED`）。
- 反例：锁定检查在 SELECT 之前。若探测手机号已写入 `auth:lock:*`，接口返回 403/1002，**根本打不到缺列**，不能当成 schema 健康，也不能当成缺列。
- 反例：网关/服务未起是 502/503 或连接失败，不是缺列。

**当前状态**：新装库（`01-user-db.sql` 已含 `role`）正常；旧卷升上来会静默缺列；README 快速开始只写 compose up + 启动服务。

**期望状态**：存量库有且仅有一个迁移入口；README 写明 initdb.d 仅首次生效；冒烟前置能把「500/9999」判失败，且不把 403/503 误判成缺列。

## What Changes

- 新增 `scripts/db/migrate.sh`：按文件名顺序遍历 **仅** `sql/migrations/*.sql`（禁止跑 `sql/0*.sql`），经 `docker exec -i` 注入 MySQL，失败即停，可重复执行。
- 新增 `sql/migrations/README.md`：新建库走 `sql/0*.sql`；存量库必须跑迁移入口；initdb.d 不会给已存在的库补列。
- README「快速开始」在 `docker compose up -d` 之后补「已有库升级」；「冒烟验证」表增加登录探测行（判据见下，不要写「期望永远 1001」这种过粗句子）。
- 新增 `scripts/smoke/smoke-schema.sh`，判据：
  - **硬失败**：HTTP 500 或 body `code=9999`（schema/系统错误）
  - **通过**：HTTP 401 且 `code=1001`（凭据错误，说明 SELECT 已跑通）
  - **服务未就绪**：连接失败 / HTTP 502 / 503 → 非 0 退出，提示先起网关与 user-service，禁止写成缺列
  - **账号锁定**：HTTP 403 或 `code=1002` → 非 0 退出，提示换未锁定手机号，禁止写成缺列
- `docs/项目速览手册.md` 的 sql 口径改为「建表脚本 + 存量迁移入口」。

**明确不做**：

- 不引入 Flyway / Liquibase / 新依赖。
- 不引入 Testcontainers，不加打真库的 Java 集成测试。
- 不改现有 3 个 migration SQL 的业务逻辑，不改 `sql/01-04`。
- 不把 migrations 挂进 `docker-entrypoint-initdb.d`（`add-idx-record-seq.sql` 有意不进首次 init，以便复现无索引压测基线）。
- 不改 Java、不改 CI、不开新业务模块、不做 S2/S3。
- 公开 `.md` **不要出现口径禁用词本身**（CI 对 `*.md` 做 grep，禁用词写进提案/README 会让 CI 红）。口径自检只跑 `.github/workflows/ci.yml` 里已有的 step。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — MODIFIED「数据库初始化」；ADDED「存量库迁移入口」「登录路径 schema 漂移可观测」。

### 受影响的代码
- 新增 `scripts/db/migrate.sh`、`sql/migrations/README.md`、`scripts/smoke/smoke-schema.sh`
- 修改 `scripts/smoke/README.md`、根 `README.md`、`docs/项目速览手册.md`

### 用户影响
- 从旧数据卷升级时多一步：`bash scripts/db/migrate.sh`。新装库行为不变。

### API 变更
- 无对外路径变更。存量库补齐 `role` 后，凭据错误登录从 500/9999 回到既有契约 401/1001。

### 需要迁移
- [x] 数据库迁移（补执行入口，不是再写一套 DDL）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新

## 时间线评估

小：约 0.5 天。

## 风险

- **`add-idx-record-seq.sql` 被默认 migrate 执行后，无索引压测基线不可复现** → 缓解：默认入口仍遍历全部 migrations（升级要完整）；README 写明复现无索引基线须全新卷且不要跑 migrate。
- **Windows PowerShell 重定向与 `docker exec -i < file.sql` 不一致** → 缓解：只提供 bash 入口，与现有 smoke 一致，用 Git Bash 跑。
- **连错 MySQL** → 缓解：只 `docker exec` 进 `sport-verify-mysql`，不连宿主机 3306。
- **探测号已锁定，冒烟假绿或假红** → 缓解：锁定在 SELECT 之前，403 既不能当通过也不能当缺列；脚本显式分支。
- **把 migrate 塞进每次 compose up** → 缓解：明确不做。

## 备注

- 文件头约定只写本仓库规则，不要把其他本机仓库的绝对路径写进公开文档。
- 现有 3 个 SQL 已是 information_schema 判断的幂等写法，入口只负责按序执行 + 失败即停。
