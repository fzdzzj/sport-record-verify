# TASK-173 handoff：判定链路消除跨服务重复读取与引入聚合契约（getRecordWithPoints）

> 状态：**已收口**。§1 为偏差登记；§2–§10 为结论、逐门、交付与收口终检。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。

## 0. 开工规程与门禁

- 开工读数逐位核验（Level A）：HEAD `c9618b04d955618785df52aec938c3c988060fbc`、`origin/main` `3998c09b3db69d240538e7f1aea017d51c0634a1`、`git rev-list --left-right --count origin/main...main = 0 1`；工作树未跟踪仅既有 `spec/changes/add-verify-degrade-status-index/`（零触碰）＋在途提案 `spec/changes/add-record-with-points-feign/`＋本任务目录；任务书 SHA256 `bfaff05b6f812e79eaa492fbe160712211678280ba8f1f1b8bfa7072f8727c8d` 全程零修改；命令行全程无中文（中文仅出现在 `.git/task173/` 下脚本与提交信息文件中）。
- 基线门禁实测（Level A）：offline 七模块 `36/41/33/103/143/59/10` 全绿 rc=0、Skipped 全 0；`--static=verify-service` rc=1 且 Checkstyle 严格 **862**；词面门四形态全 ZERO_HIT rc=1（正向探针 rc=0）；在途契约门 `--open TASK-173 --baseline=c9618b04…` rc=0。
- 规程：① 开工逐位核对＋基线门禁实测；② 生产实现（api → record-service → verify-service）；③ 测试补齐；④ 门禁复跑；⑤ C-01 提交；⑥ tasks.json 闭环＋PLAN 纯追加＋两件套＋C-02 提交；⑦ 收口终检＋C-03 回填。

## 1. 偏差登记

### 1.1 任务书计数口径差（非仓库态不符，未自行订正任务书）

| 项 | 任务书记 | 本机实测 | 判定 |
| --- | --- | --- | --- |
| §5 受保护 token 条数 | 标题「23 个」 | 实列 **24** 项 | 按 24 项逐项同法（`grep -cF` 行命中）实测，开工基线与任务书所载逐位一致、收口全等，只增不减成立；标题数字系计数口径差 |

### 1.2 执行侧偏差与说明

1. **§2.3 替换片段首行换行**：任务书片段 `RecordWithPointsDTO recordWithPoints = recordApi.getRecordWithPoints(recordId).getData();` 为 99 字符，而 verify-service 是 `--static` 唯一扫描模块且 LineLength=80（开工基线即含 LineLength 违规实证）——照抄将新增 1 处违规（863>862），违反 §6.2 与提案停止条件 4。处置：仅把该语句拆为两行（类型与变量名一行、调用表达式缩进续行），语义与行为逐字不变；其余替换片段语义逐字落地。同位置原注释行同步改写为聚合契约语义（两行），非逻辑改动。
2. **首轮测试红 1 例与修正**：`getRecordWithPoints_routesByUserIdAndAssemblesDto` 首跑失败——MyBatis-Plus `LambdaQueryWrapper` 的 `paramNameValuePairs` 为懒渲染，`selectList` 被 mock 时不经 SQL 渲染、参数表为空，`containsValue` 断言不成立。修正：测试内以 MP 单测惯用法 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrackPoint.class)` 注册列缓存后渲染 `getSqlSegment()`，断言段内含 `user_id =` 与 `seq ASC`、参数表含 `100L`/`1L`。修正后 `--pl record-service test` rc=0（105 全绿），随后全量 428 全绿。
3. **契约门时序**：`--open TASK-173 --baseline=c9618b04…` rc=0 的实测在 `handoff.md` 建立之前（此时本任务无只改清单 claims，历史任务足迹均不在工作树；TASK-169 同口径）。handoff 建立后再跑 `--open`：既有脏项 `spec/changes/add-verify-degrade-status-index/` 的 4 个未跟踪文件恒在判据 B 实际改动集内，将被记「改动集未声明」而 rc=1——此为契约工具对零触碰未跟踪目录的既知表达边界（TASK-161 §203、TASK-167 G10 登记过的同一模式），非本任务超范围改动。收口判据 B 以无参（baseline=HEAD）为准。
4. 词面门脚本、token 计数脚本、提交信息文件等原始物料均在 `.git/task173/`（不入库）；全程逐路径 `add`，无 `git add -A`/`git add .`、无 `git stash`、未 `push`、未建 PR。

## 2. 一句话结论

**完成**：聚合契约 `getRecordWithPoints` 三层落地——api 契约（DTO + 接口声明 + 4007 不可软降级 Fallback）、record-service 聚合查询（单次查 `sport_record` 解析 `user_id` 后单分片查 `track_point`）、verify-service 判定前预取接入；判定前 Feign 往返 2→1、`sport_record` 查询 2→1；既有 `getRecord` 与 `listPoints` 接口及实现 100% 未动。全项门禁通过：offline 七模块 `36/41/33/105/144/59/10` rc=0（全仓 425→**428**、Skipped 全 0）、Checkstyle 严格 **862** 未增、词面门四形态 ZERO_HIT rc=1、契约门在途 `--open` rc=0＋收口无参 rc=0、`git diff --check` rc=0。

## 3. 只改清单

相对开工基线 `c9618b04`，本任务实际改动的 14 条路径：

```
api/src/main/java/com/sportverify/api/record/dto/RecordWithPointsDTO.java
api/src/main/java/com/sportverify/api/record/RecordApi.java
api/src/main/java/com/sportverify/api/record/RecordApiFallback.java
record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
record-service/src/main/java/com/sportverify/record/controller/InternalRecordController.java
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
spec/changes/add-record-with-points-feign/proposal.md
spec/changes/add-record-with-points-feign/specs/sport-record-verify/spec-delta.md
spec/changes/add-record-with-points-feign/tasks.json
work/mailbox/tasks/TASK-173/spec.md
work/mailbox/tasks/TASK-173/handoff.md
work/mailbox/PLAN.md
```

前 8 条为 C-01（生产实现与测试，8 files / +151 −7，其中 `RecordWithPointsDTO.java` 为新建，其余 7 条为修改）；第 9–11 条为既有未跟踪在途提案三件套首次入库（proposal 与 spec-delta 正文零修改、tasks.json 四任务全步 completed＋passes）；第 12 条任务书零修改入库（SHA256 见 §0）；第 13 条为本 handoff；第 14 条 PLAN.md 纯追加（1505→1518 行、LF、无 BOM）。显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（4 文件，开工即存在，保持未跟踪原样）；其余 5 个在途未定/测量提案目录；主规格与 `spec/changes/archive/**`；一切 `scripts/**`、`pom.xml`、配置、SQL。原始证据日志位于 `.git/task173/`（不入库）。

## 4. 生产实现细节

- **api**：`RecordWithPointsDTO` 逐字取任务书 §2.1.1（Lombok `@Data/@NoArgsConstructor/@AllArgsConstructor`、`Serializable`、`serialVersionUID=1L`、字段 `SportRecordDTO record` + `List<TrackPointDTO> points`）；`RecordApi.getRecordWithPoints` 声明逐字取 §2.1.2，置于 `listPoints` 之后；`RecordApiFallback` 覆写逐字取 §2.1.3（`throw fail("getRecordWithPoints:" + recordId, cause)`），与既有显式失败工厂同路径产出 `RECORD_SERVICE_UNAVAILABLE(4007)`，绝不返回空轨迹或伪造成功。
- **record-service**：`SportRecordService.getRecordWithPoints` 逐字取 §2.2.1——先 `sportRecordMapper.selectById(recordId)`，为空抛 `RECORD_NOT_FOUND(3001)` 且不再触轨迹表；命中后 `BeanUtils.copyProperties` 组装 `SportRecordDTO`，再以 `(record_id, user_id)` 等值条件 + `orderByAsc(seq)` 单分片查询 `trackPointMapper.selectList` 并 `toDto` 映射，`new RecordWithPointsDTO(recordDto, points)` 返回；`InternalRecordController` 端点逐字取 §2.2.2。既有 `getRecord`/`listPoints`/`pagePoints` 等端点与方法体零改动。
- **verify-service**：`VerifyService.verify(recordId)` 判定前预取替换为 §2.3 语义（换行偏差见 §1.2-1）：单次 `recordApi.getRecordWithPoints(recordId)`，`recordWithPoints == null || getRecord() == null` 抛 3001，随后 `record`/`points` 局部变量语义与原名保持；灰度路由、规则引擎判定、outbox 同事务落库、状态回调、Caffeine 缓存等后续逻辑零改动；`reconcileCallback` 补偿路径与 `reviewAppeal` 的 `getRecord` 调用零改动。

## 5. 测试细节

- `SportRecordServiceTest` +2：`getRecordWithPoints_routesByUserIdAndAssemblesDto`（单次 `selectById` 恰 1 次 + 单次 `selectList` 恰 1 次 + SQL 段含 `user_id =`/`seq ASC` + 参数表含 `100L`/`1L` + 组装 DTO 字段与 points 顺序；修正过程见 §1.2-2）；`getRecordWithPoints_recordNotFound_throws`（3001 + `selectList` `never()`）。
- `VerifyServiceTest`：`stubHappyVerify` 打桩适配为 `when(recordApi.getRecordWithPoints(1L)).thenReturn(Result.success(new RecordWithPointsDTO(record(1L, VERIFYING, RUNNING, 0), List.of(point()))))`；新增 `verify_usesAggregatedFetch_neverCallsLegacyEndpoints`（`getRecordWithPoints(1L)` `times(1)`、`getRecord(1L)`/`listPoints(1L)` `never()`，逐字满足 §2.4.2）；`verify_cachedFinal…`/`verify_dbFinal…` 两用例的 `getRecord` 打桩保持（`reconcileCallback` 契约未变）；无任何既有用例被翻转。
- 测试数：record-service 103→**105**（+2）、verify-service 143→**144**（+1）、全仓 425→**428**（+3）；Failures/Errors/Skipped 全 0。

## 6. 逐门 G0–G8

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工基线 | HEAD/origin/main/rev-list/工作树/任务书 SHA256 | 逐位一致（见 §0） | 过 |
| G1 offline 全模块 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | 七模块 `36/41/33/105/144/59/10`、Failures/Errors/Skipped 全 0、BUILD SUCCESS、rc=**0** | 过 |
| G2 Checkstyle | `bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service` | 第 1 段 BUILD SUCCESS；第 2 段 `You have 862 Checkstyle violations`，rc=**1** | 过（严格 862 与开工基线持平，未增） |
| G3 词面门 | 正则自 ci.yml 现场提取，四形态 `git grep -n -I -iE` | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 全 **ZERO_HIT rc=1**；正向探针 rc=0 | 过 |
| G4 契约门 | 在途 `--open TASK-173 --baseline=c9618b04…` rc=**0**（handoff 建立前，见 §1.2-3）；收口无参 rc=**0** | 过 |
| G5 空白门 | `git diff --check` | rc=**0** | 过 |
| G6 只改清单 | `git diff --name-only c9618b04` + 未跟踪清单 | 恰 §3 的 14 路径（C-01 的 8 条 + 提案三件套 + 两件套 + PLAN）；既有脏项保持未跟踪原样 | 过 |
| G7 兼容红线 | 既有 `getRecord`/`listPoints` 接口、实现、端点与既有用例 | 全部零改动、零翻转（diff 仅 §3 清单；既有用例文件仅追加新用例与打桩适配） | 过 |
| G8 受保护 token | PLAN.md 行命中数（`grep -cF`）只增不减 | 24 项基线/收口逐项全等（见 §7） | 过 |

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`，基线 vs 收口）

| token | 基线 | 收口 | token | 基线 | 收口 |
| --- | --- | --- | --- | --- | --- |
| `13.4` | 16 | 16 | `36525962432` | 13 | 13 |
| `18.0` | 18 | 18 | `36586847965` | 12 | 12 |
| `73.93` | 17 | 17 | `36438897772` | 13 | 13 |
| `68.8` | 13 | 13 | `36399582548` | 12 | 12 |
| `6315` | 14 | 14 | `36098038547` | 12 | 12 |
| `1.8612` | 13 | 13 | `2806` | 19 | 19 |
| `3.3066` | 13 | 13 | `598` | 12 | 12 |
| `5.7056` | 13 | 13 | `36736221648` | 11 | 11 |
| `9.408` | 13 | 13 | `36808102571` | 6 | 6 |
| `36821040708` | 4 | 4 | `36845152965` | 3 | 3 |
| `36871294588` | 3 | 3 | `36880083885` | 4 | 4 |
| `36958994260` | 4 | 4 | `36976873215` | 3 | 3 |

无任一 token 减少。口径注：以 `grep -cF`（fixed-string 行命中）同法实测，24 项与任务书 §5 所载逐位一致；若用默认 regex 口径，`18.0` 由 22→24 系本记录两处引用 HEAD 哈希 `c9618b04…`（"18" + 任意字符 + "0"）所致，非实质差异。

## 8. 交付物

- 生产：api 聚合契约三件（DTO 新建、接口声明、Fallback 覆写）、record-service 聚合查询与端点、verify-service 预取接入（C-01，8 files / +151 −7）。
- 测试：record-service +2、verify-service 打桩适配 +1（含于 C-01）。
- 提案三件套入库（在途态）：`spec/changes/add-record-with-points-feign/`（tasks.json 4 任务 allPass）。
- 台账：`work/mailbox/PLAN.md`（纯追加 TASK-173 验收记录，1505→1518 行）。
- 任务两件套：`work/mailbox/tasks/TASK-173/spec.md`（零修改，SHA256 见 §0）、本 `handoff.md`。

## 9. 未覆盖项与不得推出的结论

1. `--mode=online` 与 CI 未跑；`--static` 在 checkstyle 处即失败，spotbugs/pmd **未覆盖**；**未达外部门槛**（未 push，push 须用户显式单次授权）。
2. 聚合收益为结构性推证＋mock 交互断言（预取恰 1 次、原单接口 0 次、分片键在 SQL 段与参数表中实证）；**未在双服务真环境实测**，不得据本任务推出任何生产端到端延迟、吞吐或 DB 负载下降的具体数字。
3. 既有 `getRecord` 与 `listPoints` 接口及实现完整保留（`reconcileCallback` 仍用 `getRecord`）——不得据此推断旧接口已废弃或可移除。
4. 不翻案 TASK-143~172 任何结论与历史数字；`relay-batch-mark-enabled`（true）与 `relay-send-concurrency`（2）生产默认态未在本轮改动。
5. 本文件不含词面门正则字面量；原始物料（四形态输出、探针、token 计数、offline/static 日志、契约门输出）均在 `.git/task173/`（不入库）。

## 10. 提交回填

| 提交 | 内容 | 文件数 / 行数 |
| --- | --- | --- |
| `c34812d` | C-01 `feat(api): 新增 getRecordWithPoints 聚合契约并接入 verify 判定链路（TASK-173）`（api 三件 + record-service 实现 + verify-service 接入 + 两处测试） | 8 files / +151 −7 |
| `（C-02）` | C-02 `docs(mailbox): 登记 TASK-173 验收记录与任务两件套（TASK-173）`（提案三件套 + PLAN 纯追加 + TASK-173 两件套） | 6 files |
| `（C-03）` | C-03 `docs(mailbox): 回填 TASK-173 handoff 收口读数（TASK-173）`（仅本 `handoff.md`） | 1 file |

- 收口终检读数（C-02 后实测，由 C-03 回填）：契约门无参 rc=0、`git diff --check` rc=0、词面门四形态复跑全 ZERO_HIT rc=1。
- 起点 `git rev-list --left-right --count origin/main...main = 0 1`；未 push、未建 PR。
