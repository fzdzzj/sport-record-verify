# TASK-241: 前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单requestId固化加固（MEDIUM 档）

> **开工基线**：92df499（main 分支，工作树干净，TASK-240 封盘笔与交接看板更新）
> **任务级别**：MEDIUM（前台体验闭环与客户端网络鲁棒性加固：申诉管理端到端自动回填 + 提交表单幂等防重防窜）

## 1. 目标与背景

- **背景**：
  1. **申诉管理体验断层（B8：verdict 提交后需盲打 ID）**：
     在 `/verdict` 页面（`web/src/pages/verdict.page.vue`），用户对被拒记录提交申诉后仅提示 `申诉已提交 (id: xxx)`。管理员若要执行终判，需手动切换到 `/admin-appeal` 页面，并在输入框中纯手工盲打记下的 `appealId`。`/admin-appeal.page.vue` 未读取任何 URL query 参数，管理流程缺少直达通道；且终判成功后，也无直接跳回 `/verdict` 查看改判后状态的入口，端到端操作严重割裂。
  2. **提交表单客户端幂等脆弱性（B9：requestId 易被窜改且成功后易撞 3004）**：
     在 `/record-submit` 页面（`web/src/pages/record-submit.page.vue`）：
     - `requestId` 输入框与「重新生成」按钮在 `submitting=true`（网络飞包期间）未被锁定，用户在等待网络响应时若误触更改，会导致后续重发时键不一致失去幂等保护；
     - 若提交失败或超时，用户可能因困惑而手动重新生成 `requestId`，导致服务端无法根据原 `requestId` 判定重试而产生重复记录；
     - 提交成功后 `requestId` 仍停留在原值，若用户在同一界面直接修改点集或类型提交下一笔记录，会直接撞入 3004（重复提交冲突）。
- **目标**：
  1. **B8 申诉复核管理后台端到端闭环**：
     - `web/src/pages/verdict.page.vue`：申诉成功时（`appealResult.id`），渲染直达链接或快捷按钮直通 `/admin-appeal?id=${appealResult.id}`（「去管理后台审核 &rarr;」）；
     - `web/src/pages/admin-appeal.page.vue`：挂载时读取 `route.query.id` 或 `route.query.appealId`，自动回填至表单 `form.id`，消除手工盲打；终判成功后在结果展示区提供直达链接直通 `/verdict?id=${result.recordId}`（「查看记录最新判定结果 &rarr;」），形成完整操作闭环。
  2. **B9 提交表单客户端幂等加固**：
     - `web/src/pages/record-submit.page.vue`：
       - **防空防窜**：提交前确保 `requestId` 非空有效，`submitting=true` 期间锁定输入框与「重新生成」按钮；
       - **重试固化**：提交失败（网络超时/异常等）时严格保持当前 `requestId` 不变，并提示用户直接点击可安全重试（服务端将做幂等校验）；
       - **下一条轮换**：提交成功后提供「准备下一条（自动刷新 requestId）」操作，点击后安全轮换至全新 `requestId`，避免后续提交撞 3004；
       - **3004 友好反馈**：捕获 3004 时明确提示「重复提交拦截（幂等命中，返回原记录）」。
  3. **单元测试与契约强化**：
     - 在 `web/src/api/__tests__/client.test.ts` 补充针对 `submitAppeal` 与 `reviewAppeal` 的路由契约单测；
     - 确保 `pnpm run type-check` 与 `pnpm test` 零错误全绿通过。

## 2. 修改白名单

严格限定在以下 6 个文件内：

- 前端页面与交互（共 3 文件）：
  - `web/src/pages/verdict.page.vue`
  - `web/src/pages/admin-appeal.page.vue`
  - `web/src/pages/record-submit.page.vue`
- 前端测试（共 1 文件）：
  - `web/src/api/__tests__/client.test.ts`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-241/spec.md`
  - `work/mailbox/tasks/TASK-241/handoff.md`

> **红线约束**：
> - 除上述 6 个文件外，后端代码、网关、`package.json`、根 `pom.xml`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖。

## 3. 核心设计与判别式要求

### 3.1 申诉管理闭环与自动回填（verdict.page.vue & admin-appeal.page.vue）

- **verdict.page.vue**：
  在申诉成功的 alert 消息中增加直通链接：
  ```vue
  <a-alert v-if="appealResult" type="success" class="mt-2">
    <template #message>
      <div class="flex items-center justify-between">
        <span>申诉已提交 (id: {{ appealResult.id || 'n/a' }})</span>
        <router-link v-if="appealResult.id" :to="`/admin-appeal?id=${appealResult.id}`" class="text-blue-600 hover:underline text-xs">
          去管理后台审核 &rarr;
        </router-link>
      </div>
    </template>
  </a-alert>
  ```
- **admin-appeal.page.vue**：
  引入 `useRoute` 与 `onMounted`，在挂载时自动读取并填充 query 参数：
  ```typescript
  import { ref, computed, onMounted } from 'vue'
  import { useRoute } from 'vue-router/auto'
  
  const route = useRoute()
  
  onMounted(() => {
    const qId = route.query.id || route.query.appealId
    if (qId) {
      form.value.id = String(qId)
    }
  })
  ```
  在终判成功的结果卡片中渲染返回判定页的链接：
  ```vue
  <div v-if="result.recordId" class="mt-2 text-xs">
    <router-link :to="`/verdict?id=${result.recordId}`" class="text-blue-600 hover:underline">
      查看记录最新判定结果 &rarr;
    </router-link>
  </div>
  ```

### 3.2 提交表单客户端幂等加固（record-submit.page.vue）

- **在途防篡改与防空**：
  - `genRequestId()` 在 `submitting.value` 为 true 时直接短路；
  - 表单上的 `a-input`（requestId）与「重新生成」按钮添加 `:disabled="submitting"` 绑定；
  - `onSubmit()` 开头检查：`if (!requestId.value || !requestId.value.trim()) genRequestId()`；
- **重试状态保留与下一条轮换**：
  - 在提交成功时（`res.data?.recordId` 且 `!res.data?.duplicated`），提供按钮：
    ```vue
    <a-button size="small" class="mt-2 ml-2" @click="prepareNextRecord">准备提交下一条（刷新 requestId）</a-button>
    ```
  - `prepareNextRecord` 方法实现：生成新的 `requestId`，并将 `submitResult.value = null`，避免后续提交撞 3004；
  - 在 `submitError` 展示区增加文案提示：「网络抖动或失败，保持当前 requestId 再次点击提交可安全重试（服务端将做幂等防重）」。

### 3.3 单元测试适配（client.test.ts）

- 新增 `submitAppeal` 测试：验证请求打向 `POST /record/api/records/{id}/appeal` 且载荷正确；
- 新增 `reviewAppeal` 测试：验证请求打向 `POST /admin/api/appeals/{id}/review` 且载荷正确。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. 前端验证（在 `web` 目录下执行）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：35+ 用例全绿；
  2. 契约门禁：
     - `bash scripts/verify/mailbox-contract.sh --open=TASK-241 --baseline=92df499`（退出码 0，两件套齐备且白名单一致）；
  3. `git diff --name-only 92df499` 严格全等于上述 6 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(web): TASK-241 申诉管理自动回填与记录提交幂等固化（C-01 实施笔）`（恰 4 个代码与测试文件）
  - C-02 台账笔：`docs(mailbox): TASK-241 申诉闭环与表单幂等加固验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填

- **前端类型门禁（已回填）**：`pnpm run type-check` → 通过，0 错误
- **前端单测门禁（已回填）**：`pnpm test` → 通过，37/37 用例全绿（≥35）
- **契约门禁（已回填）**：`scripts/verify/mailbox-contract.sh --open=TASK-241 --baseline=92df499` → 通过，退出码 0
- **提交（已回填）**：C-01 实施笔哈希：`8f7601d`；C-02 台账笔为本 handoff 所在提交（`docs(mailbox)`，自指哈希，实际以 `git log` 为准）。
- **红线复核（已回填）**：`git diff --name-only 92df499` 严格全等于 6 个白名单文件；零新依赖；未 push 未建 PR。

> 台账详情见 `work/mailbox/tasks/TASK-241/handoff.md`。
