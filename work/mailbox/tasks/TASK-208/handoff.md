# TASK-208 回传交接（用户自助改密与全量 refresh 吊销）

## 1. 编号、基线与外部状态

- 课题：add-change-password（用户自助改密码 + 改密即全量吊销该用户所有存活 refresh）。
- 分级：**FULL**（安全语义；NEW 4-block 复核：Git 链 + only-changed-list 合并 / 红线抽查 / 门禁复跑 / 证据链一致性）。
- 开工基线：`2d18d69`（派发笔，含提案三件套 proposal/spec-delta/tasks.json 与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- 未 push、未建 PR；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 恰 10 文件 = §4 白名单；C-02 恰 4 台账文件）

C-01（fix(auth) 实施笔 `6bd3cd5`）：

- user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
- user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java
- user-service/src/main/java/com/sportverify/user/dto/ChangePasswordRequestDTO.java
- user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
- user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java
- web/src/pages/settings.page.vue
- web/src/pages/index.page.vue
- web/src/api/client.ts
- web/src/typed-router.d.ts
- README.md

C-02（docs(mailbox) 台账笔，本 handoff 所在提交）：

- spec/changes/add-change-password/tasks.json（勾选）
- work/mailbox/tasks/TASK-208/spec.md（§7 回填）
- work/mailbox/tasks/TASK-208/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

## 3. 一句话结论

自助改密全链路落地并守护「改密即全量吊销」安全语义：`POST /api/auth/change-password`
（X-User-Id 身份认定 + BCrypt 旧密码校验 + 新哈希落库 + `auth:refresh:{userId}:*` 全量删），
存量 refresh 即刻 401、access 最长仍 15min 短窗口自然过期；全部 §2.5 判别式红绿 + §6 门禁按 FULL 档留证。

## 4. 判别式红绿（AuthServiceTest +5 / AuthControllerTest +5，纯 JVM Mockito）

- **R1a（绿）**：正确旧+新 → 不抛异常；updateById 落库 hash 匹配新密码、不匹配旧密码；`auth:refresh:{userId}:*` 全部 key 被 delete。
- **R1b（红）**：旧密码错误 → 1001；不改库（updateById 不调用）、不 keys/delete，既有 refresh 键保留。
- **R1c（红）**：old==new → IllegalArgumentException（服务层语义 → GlobalExceptionHandler 400 PARAM）。
- **R1d（红）**：status=0 用户 / 用户不存在 → 401（1001，同 refresh 封禁口径）。
- **R1e（红）**：空白/过短新密码 → ChangePasswordRequestDTO @Valid 违规（Validator 断言）；合法输入零违规。
- **G1（绿）**：改密后重放改密前 refresh → 401；新密码登录 → TokenDTO 非空（200 语义）；旧密码登录 → 1001。
- **G2**：既有 AuthServiceTest/AuthControllerTest 用例零回归（user-service 全量 162 例全绿，含本课题 +10）。

## 5. §6 门禁留证

1. **offline 全量逐位**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` →
   **BUILD SUCCESS rc=0**（脚本本体为权威，pwsh 管道回显 exit 1 属 D6 伪影），逐位 **665 = 52/50/162/157/159/70/15**
   （common/gateway/record/verify/leaderboard/mapmatch 六模块逐位不变；仅 user 152→162 净增 +10，只增不减）。
2. **契约门双态**：在途 `--open TASK-208 --baseline=2d18d69` rc=**0**（判据 A 两件套齐含 1 个待办进行中 = TASK-208 仅 spec 列待办放行；判据 B 一致）；收口无参 rc=**0**。
3. **token 29 项 2030**：见 §6（post-C01 2030 + close 2030，只增不减）；本课题新文档未引入受保护 token 字面量。
4. **词面门**：CI-exact `git grep -n -I -iE <探针>` 对改动文件集（C-01 10 文件）ZERO_HIT rc=**1**；合成探针阳性对照（对 ci.yml 同款正则）HIT rc=**0**，证明 -E 口径有效、无假阴性。
5. **`git diff --check`/`--cached --check`** 均 rc=**0**；两笔 `git show --check` rc=**0**。
6. **行尾**：C-01 改文件保持基线各自身（AuthService.java/AuthController.java/AuthServiceTest.java/README.md 为 CRLF；AuthControllerTest.java/index.page.vue/client.ts/typed-router.d.ts 为 LF）；新文件 ChangePasswordRequestDTO.java / settings.page.vue 纯 LF + 末尾换行、无 BOM。
7. **web**：本机 `pnpm run build` 因 esbuild 清理 Windows temp 目录 `Access is denied` 受阻（环境/toolchain 面，非代码错，模块已 transform）；fallback `pnpm run type-check`（vue-tsc --noEmit）**rc=0**，登记 CI web job 为权威（TASK-205 D-esbuild 先例）。

## 6. token 时点读数

- token 29 项 tracked `git grep -cF` 全量 SUM：post-C01 **2030**、close **2030**（开工基线 2030，只增不减；新文档零 token 字面量）。

## 7. 异常与偏差登记

- **D1（台账笔自指哈希）**：本 handoff 无法记录 C-02 自身哈希，沿 TASK-178/183/204/205/206/207 同型先例，以 C-02 实际提交哈希为准（`git log --oneline`）。
- **D2（web build 本机受阻）**：本地 `pnpm run build` 因 esbuild 在 Windows 清理临时目录报 `Access is denied` 退出 1（环境面，非代码错）；改走 TASK-205 D-esbuild 先例——`vue-tsc` type-check rc=0，登记 CI web job 为权威构建口径。
- **D3（pwsh 回显伪影）**：命令经 pwsh 管道时 bash.exe 的 stderr 噪音使外层回显 exit 1，脚本本体 `BUILD SUCCESS rc=0` 为权威口径（沿 TASK-207 D6）。
- **停止条件（§3）**：无一条触发——白名单无越界、Redis 仅 stringRedisTemplate 模式删（无 cluster 语义问题）、测试范式无冲突、无新静态违规、offline 基线其余六模块逐位不变。

## 8. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**；token close=**2030**；`git diff --check` rc=0；`git diff --name-only 2d18d69` 对照恰 **14 文件**（C-01 10 + C-02 4），对 api 模块/gateway/common/其他服务/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/` 排除）。

## 9. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、真实跨进程 cookie/X-User-Id 链路未跑；未 push、未建 PR，无 CI run，外部终验待指导侧推动推送后补记。
- 未声明 `@Transactional`：改密「DB 落新 hash + Redis 吊销」两步为最终一致（ADR-0009 口径），若 Redis 删除失败服务抛 500（如实报错不虚假成功），重试时旧密码已失效属安全侧正确结果。
- 无性能/吞吐声明：keys(pattern) 仅按单用户前缀取，基数极小，未作规模承诺。
