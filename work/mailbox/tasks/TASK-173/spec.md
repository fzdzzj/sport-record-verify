# TASK-173 spec：判定链路消除跨服务重复读取与引入聚合契约（getRecordWithPoints）

## 0. 硬约束与红线（继承 TASK-158/159/165/166/167/170/171/172 §0，逐字适用）

1. 本任务书是唯一权威。开工先逐位核对 §3 开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写临时脚本文件再执行；提交信息一律使用 UTF-8 无 BOM 文件配合 `git commit -F <file>`。
3. bash 一律写成 `.sh`（或 python `.py`）文件再用 `D:\git\Git\bin\bash.exe <路径>` / `python <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；严禁裸 mvn、严禁并发执行任何 Maven 命令（Maven 命令必须严格串行执行）。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 必须由用户显式单次授权。
6. **零触碰名单**：`spec/changes/add-verify-degrade-status-index/`（未跟踪目录，任何任务不得收编）；其余 5 个在途未定/测量提案目录（`measure-verify-mark-sent-spring-paired-cost`、`prove-verify-outbox-relay-concurrency-scaling`、`measure-verify-mark-sent-admin-window`、`measure-submit-pool-capacity`、`prove-verify-mark-sent-wait-attribution`）本轮零触碰。
7. 不翻案、不改写任何已入库结论与历史数字（TASK-143~172）。
8. `work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与顶端外部门槛叙事）。
9. 词面门红线：**任何要入库的文档都不得原样内嵌词面门的正则字面量或敏感词**。
10. **向后兼容性与容错红线**：既有 `getRecord` 与 `listPoints` 接口及实现 100% 保持不动；`RecordApiFallback` 必须严格遵循「不可软降级」规范（服务不可用时显式抛出 4007 `RECORD_SERVICE_UNAVAILABLE`，严禁返回空轨迹或伪造成功）。

---

## 1. 唯一目标与任务背景

### 1.1 背景
在 TASK-171/172 收口后，outbox relay 性能线（调度 500ms、分块标记 chunk 25、批内并发 N=2、单行重构 sendRow、主规格并入 163 Requirements）已正式封盘。
此时，判定消费链路的前置瓶颈浮现（见 `work/mailbox/后端优化机会总览-2026-09-26.md` §3 P2 项）：
`verify-service` 的 `VerifyService.verify(recordId)` 在判定开始前依次执行：
1. `recordApi.getRecord(recordId)`（Feign HTTP GET `/internal/records/{recordId}`）；
   - 在 `record-service` 侧：调用 `sportRecordMapper.selectById(recordId)`；
2. `recordApi.listPoints(recordId)`（Feign HTTP GET `/internal/records/{recordId}/points`）；
   - 在 `record-service` 侧：为保证 ShardingSphere 单分片路由，再次调用 `sportRecordMapper.selectById(recordId)` 解析出 `user_id` 分片键，再查 `trackPointMapper.selectList`；
3. 规则引擎判定；
4. `recordApi.statusCallback(...)`（Feign HTTP POST `/internal/records/{recordId}/status-callback`）。

由此产生：
- 两次独立的跨服务 Feign HTTP 往返；
- `record-service` 侧对 `sport_record` 表完全重复的两次 `selectById`。

### 1.2 目标
引入拉取记录与轨迹聚合契约 `getRecordWithPoints`，一次性获取记录元数据与轨迹点：
1. Feign 预拉取 HTTP 往返由 2 次降为 1 次；
2. `record-service` 侧对 `sport_record` 表的查询由 2 次降为 1 次；
3. 严格保持分片路由携带 `user_id`（单分片路由不退化为广播）；
4. 严格继承既有「不可软降级」架构规范（抛出 4007 驱动 MQ 重试或死信）；
5. 全量单元测试用例只增不减（全仓 425 → ≥428），Checkstyle ≤862，词面门与契约门全绿。

---

## 2. 架构设计与改动清单

### 2.1 Feign 契约层（`api` 模块）
1. 新增 DTO `api/src/main/java/com/sportverify/api/record/dto/RecordWithPointsDTO.java`：
   ```java
   package com.sportverify.api.record.dto;

   import lombok.AllArgsConstructor;
   import lombok.Data;
   import lombok.NoArgsConstructor;

   import java.io.Serializable;
   import java.util.List;

   /**
    * 运动记录与轨迹点聚合 DTO（record-api 契约）。
    *
    * <p>供 verify-service 校验判定前单次拉取记录元数据与轨迹点列表，
    * 消除分开调用 getRecord 与 listPoints 造成的重复 HTTP 往返与两次 selectById 查询。</p>
    */
   @Data
   @NoArgsConstructor
   @AllArgsConstructor
   public class RecordWithPointsDTO implements Serializable {

       private static final long serialVersionUID = 1L;

       /** 记录详情 */
       private SportRecordDTO record;

       /** 轨迹点列表（按 seq 升序） */
       private List<TrackPointDTO> points;
   }
   ```
2. 在 `api/src/main/java/com/sportverify/api/record/RecordApi.java` 中增加方法：
   ```java
   /**
    * 一次性拉取记录详情与全部轨迹点（聚合契约，供 verify 校验输入）。
    * record-service 内部单次查询 sport_record 解析 user_id 并单分片路由查询 track_point。
    */
   @GetMapping("/records/{recordId}/with-points")
   Result<RecordWithPointsDTO> getRecordWithPoints(@PathVariable("recordId") Long recordId);
   ```
3. 在 `api/src/main/java/com/sportverify/api/record/RecordApiFallback.java` 中落地回退：
   ```java
   @Override
   public Result<RecordWithPointsDTO> getRecordWithPoints(Long recordId) {
       throw fail("getRecordWithPoints:" + recordId, cause);
   }
   ```

### 2.2 服务端实现（`record-service` 模块）
1. 在 `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java` 中增加方法：
   ```java
   /**
    * 一次性拉取记录详情与全部轨迹点（聚合查询，供 verify 校验输入）。
    * 先查一次 sport_record 得到元数据与 user_id，若不存在抛 3001；
    * 再按 user_id 路由对应分片查询全部轨迹点（按 seq 升序）。
    */
   public RecordWithPointsDTO getRecordWithPoints(Long recordId) {
       SportRecord record = sportRecordMapper.selectById(recordId);
       if (record == null) {
           throw new BizException(ResultCode.RECORD_NOT_FOUND);
       }
       SportRecordDTO recordDto = new SportRecordDTO();
       BeanUtils.copyProperties(record, recordDto);

       List<TrackPointDTO> points = trackPointMapper.selectList(new LambdaQueryWrapper<TrackPoint>()
                       .eq(TrackPoint::getRecordId, recordId)
                       .eq(TrackPoint::getUserId, record.getUserId())
                       .orderByAsc(TrackPoint::getSeq))
               .stream().map(this::toDto).toList();

       return new RecordWithPointsDTO(recordDto, points);
   }
   ```
2. 在 `record-service/src/main/java/com/sportverify/record/controller/InternalRecordController.java` 中暴露端点：
   ```java
   /** 记录详情与轨迹点列表聚合（供 verify 校验判定单次拉取） */
   @GetMapping("/records/{recordId}/with-points")
   public Result<RecordWithPointsDTO> getRecordWithPoints(@PathVariable("recordId") Long recordId) {
       return Result.success(sportRecordService.getRecordWithPoints(recordId));
   }
   ```
3. 保留既有 `getDto` / `listPoints` 端点不变，向后兼容。

### 2.3 消费端接入（`verify-service` 模块）
1. 在 `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java` 的 `verify(Long recordId)` 方法中：
   将原先：
   ```java
   SportRecordDTO record = recordApi.getRecord(recordId).getData();
   if (record == null) {
       throw new BizException(ResultCode.RECORD_NOT_FOUND);
   }
   List<TrackPointDTO> points = recordApi.listPoints(recordId).getData();
   ```
   替换为：
   ```java
   RecordWithPointsDTO recordWithPoints = recordApi.getRecordWithPoints(recordId).getData();
   if (recordWithPoints == null || recordWithPoints.getRecord() == null) {
       throw new BizException(ResultCode.RECORD_NOT_FOUND);
   }
   SportRecordDTO record = recordWithPoints.getRecord();
   List<TrackPointDTO> points = recordWithPoints.getPoints();
   ```
2. 其余逻辑（规则引擎判定、事件落库、补偿回调等）严格保持原样。

### 2.4 测试用例更新与补充
1. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`：
   - 新增 `getRecordWithPoints_routesByUserIdAndAssemblesDto`：验证单次查 `sportRecordMapper.selectById`，单次按分片键查 `trackPointMapper.selectList`，结果字段与顺序正确；
   - 新增 `getRecordWithPoints_recordNotFound_throws3001`：验证记录不存在时抛出 3001，且不查询轨迹点表；
   - `record-service` 单测数从 103 增至 105（+2）。
2. `verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java`：
   - 适配 `verify(1L)` 既有用例打桩：`when(recordApi.getRecordWithPoints(1L)).thenReturn(Result.success(new RecordWithPointsDTO(record, List.of(point()))));`；
   - 新增测试：断言在 `verify(1L)` 调用中，`recordApi.getRecordWithPoints(1L)` 恰好被调用 1 次，而原 `recordApi.getRecord(1L)` 与 `recordApi.listPoints(1L)` 调用次数恒为 0（`never()`）；
   - `verify-service` 单测数从 143 增至 144（+1）。

---

## 3. 开工读数（开工前逐位核验）

- HEAD：`c9618b04d955618785df52aec938c3c988060fbc`
- `origin/main`：`3998c09b3db69d240538e7f1aea017d51c0634a1`
- `git rev-list --left-right --count origin/main...main` = `0 1`
- 工作树状态：仅包含已建立的在途提案 `spec/changes/add-record-with-points-feign/` 与本任务书 `work/mailbox/tasks/TASK-173/spec.md`，以及既有白名单脏项 `?? spec/changes/add-verify-degrade-status-index/`（严禁触碰）
- 全量 offline 单元测试：`36/41/33/103/143/59/10` 全绿，Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态检查：Checkstyle 严格为 **862** 处（≤ 862）
- 词面门四形态：全 `ZERO_HIT rc=1`

---

## 4. 只改清单白名单（精确文件路径）

1. `api/src/main/java/com/sportverify/api/record/dto/RecordWithPointsDTO.java`（新建）
2. `api/src/main/java/com/sportverify/api/record/RecordApi.java`
3. `api/src/main/java/com/sportverify/api/record/RecordApiFallback.java`
4. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
5. `record-service/src/main/java/com/sportverify/record/controller/InternalRecordController.java`
6. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
7. `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
8. `verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java`
9. `spec/changes/add-record-with-points-feign/proposal.md`
10. `spec/changes/add-record-with-points-feign/specs/sport-record-verify/spec-delta.md`
11. `spec/changes/add-record-with-points-feign/tasks.json`
12. `work/mailbox/tasks/TASK-173/spec.md`
13. `work/mailbox/tasks/TASK-173/handoff.md`
14. `work/mailbox/PLAN.md`（纯追加）

**严禁修改任何未列出的文件**。

---

## 5. 受保护 tokens 基线（23 个历史 tokens，只增不减）

开工基线在 `work/mailbox/PLAN.md` 中实测行命中数：
- `13.4` = 16
- `18.0` = 18
- `73.93` = 17
- `68.8` = 13
- `6315` = 14
- `1.8612` = 13
- `3.3066` = 13
- `5.7056` = 13
- `9.408` = 13
- `36525962432` = 13
- `36586847965` = 12
- `36438897772` = 13
- `36399582548` = 12
- `36098038547` = 12
- `2806` = 19
- `598` = 12
- `36736221648` = 11
- `36808102571` = 6
- `36821040708` = 4
- `36845152965` = 3
- `36871294588` = 3
- `36880083885` = 4
- `36958994260` = 4
- `36976873215` = 3

收口时 `PLAN.md` 的所有 token 出现次数必须 ≥ 基线值。

---

## 6. 验证命令与闭环标准

1. 离线全量测试：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test`
   - 要求：退出码 0，全模块全绿，`record-service` 测试数 103 → ≥105，`verify-service` 测试数 143 → ≥144，全仓测试数 425 → ≥428，Skipped 全 0。
2. 静态检查门禁：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --static=verify-service`
   - 要求：Checkstyle 违规总数 ≤ 862。
3. 词面门四形态：
   - 提取 CI 正则并在 default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 四种形态下实测，全部 `ZERO_HIT rc=1`。
4. 契约校验门禁：
   - 在途测试：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open TASK-173 --baseline=c9618b04d955618785df52aec938c3c988060fbc`，退出码 0；
   - 收口无参测试：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh`，退出码 0。
5. 格式与空白检查：
   `git diff --check` 退出码 0。
