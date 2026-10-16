# TASK-256 台账（handoff）

> **TASK**：E 系批4b-1——后端治理八项（E15+E18+E19+E20+E22+E25+E28+E30，FULL 档）
> **开工基线**：`e1776aa`（main，立项笔 `e1776aa` 后工作树干净）
> **方案**：2026-10-16 E 系批4b 指导侧立项侦察（12 项逐项亲验全部坐实），用户拍板「批4b 测试可观测」立项；批4b-1 后端八项
> **状态**：已终裁封盘（2026-10-16：实施 R1-R8 全落地七门禁全绿 + 指导亲核三块 PASS + 独立复核五块全 PASS 零阻断、4 项非阻断登记，指导侧终裁 **ACCEPT**；N-a 白名单偏差（VerifyControllerTest §2 漏列系立项笔清单笔误）经复核事实依据追认、封盘笔订正补录 §2 第 23 项；CI 外部终验运行号与终裁依据由补记笔落库登记）

## 1. 开工自证（实施侧回填）

- 开工基线核验（git status --porcelain 空 / git log --oneline 基线核对 / rev-list count）：
  - 开工时 `git status --porcelain` 为空、`git log --oneline -1` = `e1776aa docs(mailbox): 立项批4b双课题TASK-256后端八项与TASK-257前端三项`、`git rev-list --count HEAD`=774，两件套齐。
- 白名单条件项定档（第 11 条 WebSocketNotificationConfigTest 扩展 vs 新建 StompConnectAuthInterceptorTest；R3 自代理形态①②）：
  - 第 11 条定档：**扩展 `WebSocketNotificationConfigTest.java`**（E20 三用例并入，不另立新文件，保持白名单第 11 条原样在列）。
  - R3 自代理形态定档：**方案②**（保留 `@RequiredArgsConstructor`，另加 `@Autowired @Lazy` 非 final 包可见 self 字段），避免改动既有测试构造器调用语义。

## 2. 只改清单全等声明（实施侧回填）

- git diff --name-only &lt;开工基线&gt; 与 spec §2 白名单双向全等核验：`git status --porcelain` 恰为 spec §2 列 20 项业务/测试 + 另有 2 项业务/测试新文件（LoginLockoutConfigTest / UserControllerTest，均属白名单 3/22 行）+ 台账 2 项（spec.md / handoff.md，仅 C-02 阶段）；与白名单逐项双向全等，无白名单外文件，无漏改。
- 未跟踪文件为零核验（git ls-files -o --exclude-standard）：除两份新业务测试 `LoginLockoutConfigTest.java`、`UserControllerTest.java` 外无其他未跟踪文件（.trae/tmp/ 属 gitignore，未纳入）。

## 3. 门禁读数（实施侧回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端全模块 surefire（offline） | `mvn-verify.sh --mode=offline test` | 0 | 七模块 63 / 78 / 215 / 302 / 200 / 108 / 35 vs 基线 59/75/205/302/195/108/35 只增不减（common+4 gateway+3 user+10 record=302 持平 verify+5 leaderboard/mapmatch=基线持平） |
| 2 | 契约门 --open=TASK-256 --baseline=e1776aa | `mailbox-contract.sh --open=TASK-256 --baseline=e1776aa` | 0 | 判据 A 两件套齐、判据 B 清单一致（PASSED） |
| 3 | token 门 29 项 | `git grep -cF <token>` 逐项 | — | SUM=1353（开工/实施后逐位持平或只增，无回落） |
| 4 | 词面门（模式文件法 + 探针阳性对照） | git grep 权威正则（ci.yml 口径） | — | 改动文件集 ZERO_HIT；R7 净化正则字面量仅落 Java 代码，台账/文档以文字描述（词面门红线满足）；.trae/tmp 探针阳性对照后已删 |
| 5 | 静态门（checkstyle delta=0） | — | 0 | record-service 4 项 pre-existing 持平口径（白名单零新增 checkstyle 违规） |
| 6 | compose config | — | — | 白名单零 compose 文件，凭零 diff 论证不变 |
| 7 | diff 净 | `git diff --check` / `git show --check` | 0 | 新文件纯 LF + 完整末尾换行（末字节 0x0A，已核验）；改文件保持既有行尾；.trae/tmp 临时件已清理 |

## 4. 判别式逐项实施登记（实施侧回填）

- R1（E15）：`user-service/src/main/resources/application.yml` L77 注释订正 + `threshold: 5→20`（全账号兜底阈值，与 AuthService 代码默认 20 对齐，不再被 yml 覆盖打回 5）；README L223 双维度数值口径（组合阈值 5 / 兜底阈值 20）；新建 `LoginLockoutConfigTest`（YamlPropertySourceLoader + Binder 解析 classpath yml 属性树，断言 `app.auth.lock.threshold==20`，不断言 ip-threshold）。surefire 该用例 1 绿。
- R2（E18）：`RecordEventProducer` 新增 `publishSubmittedSync`（事件构造/traceId header 装配抽共用 `buildMessage`；`syncSend` try/catch 返真值 true/false）；`VerifyDegradeService` L119/L146 两补偿决策点改调同步变体（假成功修复，Broker 不可达返 false → 走 `degradeToManualReview`）。既有异步 `publishSubmitted` 两变体签名与行为零改动。`VerifyDegradeServiceTest` mock 面从 publishSubmitted 迁移到 publishSubmittedSync，断言语义不弱化。
- R3（E19）：`FriendService` 加 `@Autowired @Lazy FriendService self`（方案②，非 final 包可见）；`batchTransition` 循环内改经 `self.accept/reject` 委托（每次经 Spring 事务代理，批量两写原子性恢复）。测试 `setUp` 置 `self=service` 保既有语义；新增结构断言用例：self 置 mock 验证 batchAccept/batchReject 逐条经 self.accept/reject 委托。
- R4（E20）：`JwtUtil` 新增 `parseAccess`（parse + type==ACCESS，返回 userId；与 parseRefresh 对称，非 ACCESS 抛 JwtException）；`StompConnectAuthInterceptor` L63 改调 `parseAccess`（WS 会话强制 type=ACCESS，refresh 不能建 WS 会话）。`WebSocketNotificationConfigTest` 扩展三用例：refresh 拒 / access 过且 Principal=userId / 无效拒。
- R5（E22）：`AppealReviewDTO` `boolean pass` → `Boolean` + `@NotNull`（漏传不再静默默认 false 走维持拒绝）；`VerifyService` 三调用点 `dto.isPass()` → `Boolean.TRUE.equals(dto.getPass())`（null 防御兜底 + @Valid 权威拦截）。`VerifyControllerTest` 新增 Bean Validation 用例断言缺 pass → 1 处 `@NotNull` 违规（400 通道）。
- R6（E25）：`R2AccelRule.evaluate` 循环自 `i=2` 起（跳过首区间，首点为未计算 speed=0/dt=0 哨兵，消除伪尖峰）；开工复验 `TrackPreprocessor` 首点不回填成立（L56-61 首点 speed/dt=0 哨兵）。`VerifyEngineTest` 新增三判别：首区间伪尖峰不计 / 三真实尖峰仍命中 / 两真实+一首区间伪尖峰不命中（minCount 门槛恢复）。
- R7（E28）：`TraceIds.resolveOrCreate` 与网关 `RequestIdGlobalFilter.resolveOrCreate` 双实现同规则净化——入站 ID 仅当长度 1..64 且字符限于字母/数字/下划线/连字符时沿用，否则 newId() 重生成（不拒绝请求，不打断链路）；正则字面量仅落 Java 代码（台账/文档文字描述，满足词面门红线）。双侧测试四类对称：超长 65 / 非法字符（空格/中文/控制）/ 边界 64 与 1 / 合法沿用；既有「原样复用」中合法入参用例保留、非法入参维度翻转为「重新生成」。
- R8（E30）：`UserController.updateProfile` selectById 判存在后改 `LambdaUpdateWrapper` 单列 `set(nickname)` UPDATE（不整行回写 password_hash/role/status），DTO 构造用局部变量携带新昵称。新建 `UserControllerTest`（LambdaUpdateWrapper 断言前 `TableInfoHelper.initTableInfo` 注册 User 实体——TASK-221 教训）+ 校验（空/超 32）、USER_NOT_FOUND、auth disabled 既有行为保持。

## 5. 受限与偏差声明（实施侧回填，对照 spec §5 六项预注册）

1. E15 Nacos 运行时覆盖未核（本机无 Nacos 环境）——修复以仓库内配置面为界。
2. E18 syncSend 阻塞为有界偏差：补偿为后台定时任务，阻塞上限为 Producer 自身发送超时；主提交异步 best-effort 零改动。record 模块 surefire 持平 302（VerifyDegradeServiceTest 变更均为 mock 面平移，未新增用例数）。
3. E19 事务性修复为结构性（经代理调用），纯 JVM 单测无法验证真实回滚——以结构断言（mock self 委托）+ `@Transactional` 注解承载，真实事务回滚未实测登记。
4. E20 修复后 WS 会话凭证窗口与网关同口径收敛，真实浏览器 STOMP 流转未跑（无既有 E2E 覆盖可回归）。
5. E28 净化规则对既有合法调用方零影响（前端未自设 X-Request-Id，静态核对）；未来 ingress 超长 ID 会被重生成——行为面如实登记。
6. E25 高频采样误拒改善不做比率/延迟声明（行为修复不量化）。
- 另：Bash 脚本执行经 `D:\git\Git\usr\bin\bash.exe --login`（同 Git 安装的原生 MSYS bash）；`D:\git\Git\bin\bash.exe` 当前因 WSL「missing a registration」/ ext4.vhdx 挂载失败（环境级异常，非代码/脚本问题）无法启动，故用同安装原生 bash 等效执行仓库验证脚本；`mvn-verify.sh` 全程仍为离线权威入口。契约门 / 离线 surefire 均据此跑通。

## 6. 提交与哈希（实施侧回填）

- C-01（实施）：`a89d095` — `fix(user,record,verify,common,gateway,api): E系批4b-1后端治理八项（TASK-256）`（23 文件，+542/-32，恰白名单业务/测试 20 项 + 2 新业务测试 + README）
- C-02（台账）：`2a2f11f` — `docs(mailbox): 登记TASK-256实施完成与门禁回填（TASK-256）`（仅 spec.md + handoff.md；封盘笔回填本行哈希——C-02 提交时自指不可知，沿 git log 后补先例）

## 7. 行尾与自证（实施侧回填）

- 新文件纯 LF + 完整末尾换行（末字节 0x0A）核验：`LoginLockoutConfigTest.java`、`UserControllerTest.java` 末字节 0x0A、CRLF=0（已核验）；改文件保持既有行尾；`git diff --check` rc=0、`git show --check` rc=0；.trae/tmp 临时件（探针/监控脚本）用后清理。
- 完成 C-02 后 `git status --porcelain` 为空自证。
