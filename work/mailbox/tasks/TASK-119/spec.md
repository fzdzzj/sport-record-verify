# TASK-119 add-auth-degrade-header-strip：鉴权降级与白名单路径剥离身份头

## 背景（已核实）

`gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java` 第 79-81 行：

```java
if (!authEnabled || isWhitelisted(path)) {
    return chain.filter(exchange);
}
```

该分支**直接透传**，不剥离外部携带的 `X-User-Id` / `X-Role`。而 `app.auth.enabled` 默认 `false`
（`gateway-service/src/main/resources/application.yml:106`），且 `docker-compose.services.yml` 约定
**只有网关 8080 对宿主机全接口发布**。两者相乘即：任何人自带 `X-User-Id: 1` 打网关，下游
（record / verify / leaderboard / user）按「网关唯一注入方」的口径取值 → 冒充任意用户，无需 token。

`AuthGlobalFilter` 类注释声称的「外部伪造的同名头被冲掉」，只在**鉴权开启路径**（`headers.set`）成立；
降级分支与白名单分支（`/api/auth/**`、`/actuator/**`）都是裸透传，注释与实现不一致。

## 目标（EARS，新需求）

`#### 网关降级路径剥离身份头`
WHEN 请求未经过网关鉴权注入（鉴权开关关闭或路径命中白名单）, 系统 SHALL 在透传前剥离外部携带的
`X-User-Id` 与 `X-Role` 头。

- Scenario：降级开关下的伪造头清洗（`app.auth.enabled=false` + 外部头 → 下游不含两头）
- Scenario：白名单路径下的伪造头清洗（`/api/auth/**`、`/actuator/**` 同上）
- Scenario：鉴权路径覆盖式注入不受影响（有 token 时下游只认网关注入值）

## 实现要点

- 在透传分支 mutate 移除 `HEADER_USER_ID` / `HEADER_ROLE` 两头；
- 鉴权开启路径**维持现有 `headers.set` 覆盖式注入不动**（不改语义、不改顺序）；
- **不改 `app.auth.enabled` 默认值**（本地演示与压测兼容，profile 强制另立变更再议）；
- 不动 `JwtTokenParser` / 白名单语义 / `adminPaths` 逻辑。

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java
- gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java
- spec/changes/add-auth-degrade-header-strip/proposal.md
- spec/changes/add-auth-degrade-header-strip/tasks.json
- spec/changes/add-auth-degrade-header-strip/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-119/spec.md
- work/mailbox/tasks/TASK-119/handoff.md
- work/mailbox/PLAN.md

## 范围外（越界即视为未验收）

- 不改 `application.yml` 的注释与默认值（`enabled: false` 保持；注释与实现的措辞漂移按未决登记）
- 不引入新依赖 / 新 Maven 插件 / 新模块
- 不改 CI 工作流、不 push、不建 PR、不改 GitHub 设置
- 不自行归档本变更（并入主规格另派）
- 不动既有 8 条 `AuthGlobalFilterTest` 断言语义（只新增用例与私有辅助方法）
- 禁止 `git stash`

## 取证要求（先红后绿，两对都要留原文）

- **红（实现前先写测试）**：降级分支与白名单分支各注入伪造 `X-User-Id` / `X-Role`，
  断言下游收到的请求不含两头 → 改前必红（附失败断言原文与行号、命令、退出码）
- **绿（实现后）**：同测试通过；既有 19 条全绿不回归
- **变异验证（TASK-106 手法）**：临时删掉剥离逻辑复现红，还原后 `cmp` 零差异

## 验收命令

1. `bash scripts/verify/mvn-verify.sh --pl gateway-service test` → 定向留档（红/绿各一次）
2. `bash scripts/verify/mvn-verify.sh --mode=offline test` → 全仓 ≥285 全绿
3. `bash .trae/tmp/wording-check-119.sh` → CI 同款词面自检范围内零命中
4. `bash scripts/verify/mailbox-contract.sh`（无参数）→ 退出码 0

## 完成定义

- `work/mailbox/tasks/TASK-119/handoff.md`：只改清单 / 红绿两对取证（命令 + 关键输出 + 退出码）
  / 逐模块用例数变化 / offline 全量汇总 / 契约退出码 / 未决
- 收口记录按 `work/mailbox/PLAN.md` 开头「收口清单」格式追加（绑定 commit id、门槛来源、
  未推送显式标注、未覆盖不得写成通过）

## 约束

- 命令行禁止带中文（本机 exit 127）：中文检索用 Grep/Read 工具，含中文/正则的脚本写成
  `.trae/tmp/` 下的 UTF-8 文件再 bash 执行
- 提交信息用 `git commit -F <UTF-8 文件>`，conventional commits 前缀 + 中文描述
- 文本不得含 CI 禁用词表中的词
- Maven 验收唯一入口是 `scripts/verify/mvn-verify.sh`；本机需先
  `unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL` 并 `export JAVA_HOME=/d/develop1/jdk21`
