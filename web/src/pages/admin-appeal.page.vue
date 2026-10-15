<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="申诉终判 (ADMIN)" class="mb-4">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="403 无权限：非 ADMIN 角色禁止访问管理功能（网关返回 403/1002）。请使用 ADMIN 账号登录后重试。" show-icon />

      <div v-if="role === 'ADMIN'">
        <p class="text-gray-600 mb-4">终判 POST /admin/api/appeals/{id}/review 。从下方待审列表一键填入 appealId（自动终结盲打 ID），或手工输入。pass=true 改判通过，false 维持拒绝。</p>

        <div>
          <a-form-item label="appealId (待审列表一键填入或手工输入，必填)" required>
            <a-input v-model:value="form.id" placeholder="例如 123" style="width: 200px" />
          </a-form-item>
          <a-form-item label="operator (复核操作人)" required>
            <a-input v-model:value="form.operator" placeholder="admin" />
          </a-form-item>
          <a-form-item label="recheckResult (复核结论，可选)">
            <a-textarea v-model:value="form.recheckResult" :rows="3" placeholder="详细结论..." />
          </a-form-item>
          <div class="flex gap-2 mt-2">
            <a-button type="primary" :loading="loading" @click="onReview(true)">终判通过 (pass=true)</a-button>
            <a-button danger :loading="loading" @click="onReview(false)">维持拒绝 (pass=false)</a-button>
          </div>
        </div>

        <a-alert v-if="result" type="success" class="mt-4">
          终判成功: id={{ result.id }} recordId={{ result.recordId }} status={{ result.status }} operator={{ result.operator }}
          <div v-if="result.recheckResult">结论: {{ result.recheckResult }}</div>
          <div v-if="result.recordId" class="mt-2 text-xs">
            <router-link :to="`/verdict?id=${result.recordId}`" class="text-blue-600 hover:underline">
              查看记录最新判定结果 &rarr;
            </router-link>
          </div>
        </a-alert>
        <a-alert v-if="error" type="error" :message="error" class="mt-4" />

        <a-alert type="info" class="mt-4 text-xs">
          提示：普通用户打开此页必须看到 403，不显示表单。待审列表初载即拉取，终判成功或点击刷新后自动重载。
        </a-alert>
      </div>
    </a-card>

    <!-- 待审申诉列表卡片（B15 申诉待审闭环）：展示待审队列，一键填入审核 -->
    <a-card v-if="role === 'ADMIN'" title="待审核申诉列表 (B15)" class="mb-4">
      <p class="text-gray-600 mb-2">GET /admin/api/appeals/pending 按 id DESC 返回全部 PENDING 申诉。点击「填入审核」自动把申诉 ID 填进上方表单，终结盲打 ID。</p>
      <div class="flex items-center gap-2 mb-2">
        <a-button :loading="pendingLoading" @click="loadPending">刷新待审列表</a-button>
        <span class="text-xs text-gray-500">待审 {{ pendingRows.length }} 条</span>
      </div>
      <a-alert v-if="pendingError" type="error" :message="pendingError" class="mb-2" />
      <a-table
        :data-source="pendingRows"
        :columns="pendingColumns"
        :pagination="false"
        size="small"
        row-key="id"
        :loading="pendingLoading"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-button size="small" type="primary" ghost @click="fillIntoForm(record)">填入审核</a-button>
          </template>
        </template>
        <template #emptyText>
          {{ pendingLoading ? '加载中…' : '暂无待审申诉' }}
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router/auto'
import { getRole } from '@/utils/token'
import { reviewAppeal, fetchPendingAppeals, type AppealDTO } from '@/api/client'

const route = useRoute()
const role = computed(() => getRole() || '')

const form = ref({ id: '', operator: 'admin', recheckResult: '' })
const loading = ref(false)
const result = ref<any>(null)
const error = ref('')

// B15：待审列表状态
const pendingRows = ref<AppealDTO[]>([])
const pendingLoading = ref(false)
const pendingError = ref('')

const pendingColumns = [
  { title: '申诉ID', dataIndex: 'id', key: 'id', width: 90 },
  { title: '记录ID', dataIndex: 'recordId', key: 'recordId', width: 90 },
  { title: '申诉人', dataIndex: 'userId', key: 'userId', width: 90 },
  { title: '申诉理由', dataIndex: 'reason', key: 'reason' },
  { title: '提交时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', dataIndex: 'action', key: 'action', width: 100 },
]

/** 拉取待审申诉列表 */
async function loadPending() {
  if (role.value !== 'ADMIN') return
  pendingLoading.value = true
  pendingError.value = ''
  try {
    const res = await fetchPendingAppeals()
    pendingRows.value = res.data || []
  } catch (e: any) {
    pendingError.value = (e.code ? `[${e.code}] ` : '') + (e.message || '加载待审列表失败')
  } finally {
    pendingLoading.value = false
  }
}

/** 一键填入审核：把待审申诉 ID 填入表单输入框，供管理员直接终判 */
function fillIntoForm(record: AppealDTO) {
  form.value.id = String(record.id)
}

// B8：挂载时自动读取 query.id / query.appealId 回填表单，消除手工盲打 ID。
onMounted(() => {
  const qId = route.query.id || route.query.appealId
  if (qId) {
    form.value.id = String(qId)
  }
  loadPending()
})

async function onReview(pass: boolean) {
  if (!form.value.id || !form.value.operator) {
    error.value = 'appealId 和 operator 必填'
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const dto = {
      operator: form.value.operator,
      pass: !!pass,
      recheckResult: form.value.recheckResult.trim() || undefined
    }
    const res = await reviewAppeal(form.value.id, dto)
    result.value = res.data
    // B15：终判成功后自动刷新待审列表（该申诉已从 PENDING 流出，留在队列中会导致重复终判）
    await loadPending()
  } catch (e: any) {
    const code = e.code ? `[${e.code}] ` : ''
    error.value = code + (e.message || '终判失败')
  } finally {
    loading.value = false
  }
}
</script>
