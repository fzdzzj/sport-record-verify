# TASK-214 handoff：测试补强——mapmatch 契约与偏好鉴权路径（MEDIUM 档，纯测试新增）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-214`（提案 `spec/changes/add-test-backfill/proposal.md`，E4 侦察改判后的真实缺口收口）。
- 分级：**MEDIUM**（纯测试新增、零生产代码；指导侧亲核 + CI 外部终验收口，**免独立复核**）。
- 开工基线：`4431dbf`（派发笔，含提案三件套与任务书）；开工前 `git status --porcelain` 为空，基线自洽。
- 离线基线（任务书 §6）：684 = 52/50/162/166/159/80/15。
- C-01 实施笔：`cb5c0f1`（`test(mapmatch): 契约校验与偏好鉴权路径测试补强（TASK-214）`，恰 4 文件，+321／−0）；C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 D1 先例）。

## 2. 只改清单（相对开工基线 4431dbf，恰 8 文件 = C-01 4 + C-02 4）

- mapmatch-service/src/test/java/com/sportverify/mapmatch/dto/MapMatchRequestValidationTest.java（新建，R1 七测）
- mapmatch-service/src/test/java/com/sportverify/mapmatch/controller/MapMatchControllerTest.java（新建，R2 两测）
- mapmatch-service/src/test/java/com/sportverify/mapmatch/config/MatchPropertiesTest.java（新建，R3 两测）
- user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java（增补三测，既有测原样）
- spec/changes/add-test-backfill/tasks.json（本笔勾选）
- work/mailbox/tasks/TASK-214/spec.md（本笔 §7 回填）
- work/mailbox/tasks/TASK-214/handoff.md（本笔新建）
- work/mailbox/PLAN.md（本笔纯追加）

## 3. 红线核验（任务书 §0 / §3）

- 六服务 src/main、api 模块、common、父 pom、sql、ci.yml、mvn-verify.sh、mailbox-contract.sh、compose、web、任何 pom、.trivyignore 全程零触碰（G2 证据见 §5 第 8 条：基线 diff 仅 §2 的 8 文件）。
- mapmatch `spring-boot-starter-validation` 既有（api 模块传递 + 本模块依赖），pom 零改动即满足校验依赖，无 §3 停止条件场景。
- 禁新依赖/新插件/test 资源文件：三个新测试类均只用既有测试classpath（junit-jupiter、mockito、assertj、spring-boot-test、jakarta-validation、spring-core/context），零新增 import 依赖声明。
- §3 停止条件三条均未触发：改动未出白名单；无需任何生产/配置变更；级联校验与预注册预期完全一致（R1c 三形态全按预期触发，非疑似生产缺陷）。

## 4. 判别式红绿（§2.5 逐条）

| 判别式 | 断言 | 结果 |
| --- | --- | --- |
| R1a | 空 points → 恰 1 条 @NotEmpty 违规，propertyPath=`points`，message=轨迹点不能为空 | 绿 |
| R1b | 20000 点零违规（恰在上限内）；20001 点 → 恰 1 条 @Size 违规（path=`points`） | 绿 |
| R1c | seq=null→`points[0].seq`；lat=90.5→`points[0].lat`（纬度超出范围）；lng=null→`points[0].lng`，各恰 1 条级联违规 | 绿 |
| R1d | 全合法请求（含边界 -90/-180、90/180）零违规 | 绿 |
| R2 | Result.success 包装 code=SUCCESS、data 与服务返回值同一实例（assertSame）；请求 DTO 同实例透传（ArgumentCaptor + assertSame，防 @Data equals 结构相等假绿） | 绿 |
| R3a | `new MatchProperties()` 四默认值 200/50/25/300 逐位锁定（MapMatchServiceTest 黄金值判别式承重契约，javadoc 注明） | 绿 |
| R3b | `mapmatch.match.*` 四键 Binder 绑定覆盖 → 100/60/30/850 生效（DataSourcePasswordEnvBindingTest 范式：MutablePropertySources 不含 StandardEnvironment、`ConfigurationPropertySources.from` + `PropertySourcesPlaceholdersResolver`） | 绿 |
| R4a | authEnabled=true + X-User-Id=42 + claimed=null → getPreferences(42L) 被调、返回 SUCCESS | 绿 |
| R4b | 同态 updatePreferences(42L, body) 被调、body 原样透传、SUCCESS | 绿 |
| R4c | claimed=999≠header=42 → BizException FORBIDDEN(1002)，getPreferences/updatePreferences 均 never 调用 | 绿 |
| G1 | 既有用例零回归：offline 七模块 Failures/Errors/Skipped 全 0；common/gateway/record/verify/leaderboard 五模块逐位不变，mapmatch/user 仅净增 | 绿 |
| G2 | 生产面零 diff：基线改动集恰 §2 的 8 文件，六服务 src/main/api/common/父 pom/sql/ci/scripts/compose/web 全空 | 绿 |

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS rc=0**，逐位 **698 = 52/50/165/166/159/80/26**（基线 684 → 净增 +14：mapmatch 15→26（R1 七测 + R2 两测 + R3 两测 = +11）、user 162→165（R4 三测 = +3），common=52/gateway=50/record=166/verify=159/leaderboard=80 五模块逐位不变；Failures/Errors/Skipped 全 0）。
2. **static 既有范围双跑**（任务书 §6「沿现行口径、不扩面」）：`--static=record-service` rc=**0**（TASK-194 清零口径保持）；`--static`（缺省 leaderboard）rc=**0**（SpotBugs Medium 既有 9 处存量照旧输出不拦截，failThreshold=High）。
3. **契约门双态**：在途 `--open TASK-214 --baseline=4431dbf` rc=**0**（判据 A 两件套口径：TASK-214 仅 spec 声明进行中放行）；收口后另跑 `--baseline=4431dbf`（无 --open）rc=**0**——判据 B 实审：改动清单节 8 token 与基线 diff 全等（见 §9）。
4. **token 29 项**：见 §6。
5. **词面门**：CI-exact 口径（模式文件显式 ERE，`git grep -n -I -iE -f`，对齐 ci.yml L95 的 `-n -I -iE`）对改动 4 测试文件 **ZERO_HIT**；合成探针阳性对照 `grep -n -I -iE -f <mode> probe` **HIT rc=0**（命中探针第 2 行），证明 `-E/-i` 口径有效、无假阴性（沿 TASK-202 教训以探针坐实模式有效性）。附：改动 4 文件对凭证类字面量自检零命中。
6. **`git diff --check` / `--cached --check`** 均 rc=0。
7. **行尾**：三新文件纯 LF + 末尾换行、无 BOM、无 CR（逐字节核验：首 3 字节非 BOM、CR 计数 0、末字节 0A）；`NotificationControllerTest.java` 保持基线 LF 不变（`git ls-files --eol` i/lf w/lf 与基线一致）；台账 4 文件均 w/lf 与基线一致，tasks.json 保持末尾换行（D4 防复现）。
8. **只改清单全等**：`git diff --name-only 4431dbf` 恰 §2 的 8 文件；对六后端/api/common/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose 及 web **git diff 全空**（G2）。

## 6. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工（4431dbf 基线）账单参照 = **2030**、post-C01 = **2030**、close = **2030**（只增不减）。本课题改动集为 4 测试文件 + 4 台账文件，全部内容零凭证/口令/签名密钥字面量（词面门凭证自检零命中佐证）；沿 TASK-188 N1 / TASK-190 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准；权威 29 项清单与终测由指导侧亲核复测。

## 7. 实现要点

- **MapMatchRequestValidationTest**：纯 jakarta `Validation.buildDefaultValidatorFactory().getValidator()`，零 Spring 上下文（AuthRequestDtoValidationTest 同款）；R1c 三形态各自独立用例并断言违规恰 1 条 + propertyPath 精确前缀，锁死级联 @Valid 语义；20000/20001 边界双向锁死 @Size 上限。
- **MapMatchControllerTest**：`@RequiredArgsConstructor` 公有构造器手工 new + Mockito mock MapMatchService；javadoc 注明手工构造下 @Valid 不触发、校验语义由 R1 承载（对齐任务书 §2 范围切分）；透传断言用 ArgumentCaptor + assertSame 而非 verify(request)（@Data equals 结构相等会假绿）。
- **MatchPropertiesTest**：R3a javadoc 显式声明四默认值与 MapMatchServiceTest 黄金值的承重关系（searchRadiusMeters=300 ↔ 300/85000 半径换算与离路封顶；snapThresholdMeters=25 ↔ 22.26m 吸附 / 33.40m 离路的 matched ratio 分裂）；R3b Binder 构造逐字对齐本模块 DataSourcePasswordEnvBindingTest（`from` + `PropertySourcesPlaceholdersResolver`，无系统源隔离宿主机变量）。
- **NotificationControllerTest 增补**：三测纯追加于 TASK-187 分节之后（新分节注明 TASK-214）；R4a/R4b 走 claimed=null + header=42 路径（resolveUserId 鉴权态以网关头为权威）；R4c 复用既有 page_claimedMismatchHeader_throwsForbidden 的越权范式并加偏好服务 never 双验证；`any` 静态导入为唯一 import 变更。

## 8. 异常与偏差登记

- **D1（台账笔自指哈希）**：C-02 提交哈希在本 handoff 及 spec §7 内以「本 handoff 所在提交」指称，沿 TASK-178/183/204/205/206/207/208/209/210/211/213 先例。
- **D2（既有测数措辞偏差）**：任务书 §1/S1 记 NotificationControllerTest「既有 17 测」；开工实测为 14 测（TASK-187 分节四测 + 通用十测）。本课题严格仅追加预注册 3 测（R4a/R4b/R4c），既有 14 测零改动（diff +52/−0 全为追加），追加后文件总数恰 17，与任务书口径的「17」在 offline 实测（Tests run: 17）处汇合一致；user 模块 162→165 净增 +3 与任务书 §6 口径吻合。属任务书侦察措辞偏差，无实现影响，不触发 §3。
- **D3（token 计量口径）**：29 项权威清单由指导侧持有，执行侧以「改动集零凭证字面量 + 词面门凭证自检 ZERO_HIT」佐证 2030 持平，未独立复算 29 项全量 SUM（沿 TASK-213 MEDIUM 档同口径），指导侧亲核时可用权威清单复测。
- **D4（tasks.json 末尾换行）**：重写 tasks.json 时保留末尾换行（TASK-213 D4 教训前置规避），diff 仅布尔翻转 8 处。
- **停止条件（§3）**：无一条触发。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**；另跑 `--baseline=4431dbf`（无 --open）rc=**0**（判据 B 实审：TASK-214 handoff 相对基线在途 → 改动清单 8 token 与实际改动集全等）。
- token 收口态账单参照 SUM=**2030**（见 §6 口径说明）。
- `git diff --check` rc=0。
- `git diff --name-only 4431dbf` 对照恰 **8 文件**（C-01 4 + C-02 4）：对六后端/api/common/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose 及 web **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/tmp` 临时件已清空）。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、MockMvc/WebMvcTest 层的 400 校验映射（错误信息含字段名经 GlobalExceptionHandler 的端到端表现）未跑——入参校验语义由 Validator 级承载（任务书 §2 明示范围切分），CI 外部终验待指导侧推动推送后补记。
- R1 未覆盖 seq 负值（@DecimalMin("0")）与 lng 越界（±180 外）两形态——预注册判别式仅锁 seq=null/lat=90.5/lng=null 三形态与边界双向，未注册形态不在本课题范围。
- MatchProperties 与 Nacos 配置中心的运行时叠加未测（纯 Binder 绑定语义）；黄金值判别式本体（MapMatchServiceTest）零改动，本课题仅锁定其参数承重契约。
- 无性能/吞吐断言（任务书 §0 禁性能声明）。
