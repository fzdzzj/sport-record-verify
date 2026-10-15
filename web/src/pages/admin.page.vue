<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="ADMIN 治理面控制台">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="403 无权限访问" />
      <div v-else>
        <p>欢迎 ADMIN。使用顶部菜单「规则版本」或「申诉终判」。</p>
        <p class="text-xs text-gray-500 mt-2">仅 role=ADMIN 显示入口；直开 URL 仍展示 403。</p>
        <a-button @click="go('/admin-rules')" class="mt-2">规则版本管理</a-button>
        <a-button @click="go('/admin-appeal')" class="ml-2">申诉终判</a-button>
      </div>
    </a-card>

    <!-- Outbox 消息补偿与死信看板（TASK-246 B20） -->
    <a-card title="Outbox 消息补偿与死信看板" class="mt-4">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="死信看板为治理面能力，需 ADMIN 角色" class="mb-2" />
      <div v-else>
        <p class="text-gray-600 mb-2">
          查看 EXHAUSTED 死信行（id 倒序，上限 200）并一键重放为 PENDING（retry_count=0）重新进入投递集；
          也可按天数批量清理历史已投递（SENT）行。
        </p>
        <div class="mb-2 flex flex-wrap items-center gap-2">
          <a-button :loading="outboxLoading" @click="loadOutbox">刷新死信</a-button>
          <a-input-number v-model:value="cleanDays" :min="1" :max="365" class="w-28" />
          <a-button danger :loading="cleanLoading" @click="onCleanSent">清理 {{ cleanDays }} 天前已投递</a-button>
          <a-alert v-if="outboxInfo" type="success" :message="outboxInfo" class="mt-2 flex-1" />
          <a-alert v-if="outboxError" type="error" :message="outboxError" class="mt-2 flex-1" />
        </div>

        <a-table
          :data-source="exhaustedRows"
          :columns="outboxColumns"
          :pagination="false"
          size="small"
          row-key="id"
          :loading="outboxLoading"
          class="mt-2"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'payload'">
              <span class="text-xs text-gray-500">{{ truncate(record.payload, 48) }}</span>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag color="red">{{ record.status }}</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-popconfirm
                title="确认将该死信重置为 PENDING 并重放投递？"
                ok-text="重放"
                cancel-text="取消"
                @confirm="onReplay(record)"
              >
                <a-button size="small" type="primary" :loading="replayId === record.id">重放</a-button>
              </a-popconfirm>
            </template>
          </template>
          <template #emptyText>{{ role !== 'ADMIN' ? '需 ADMIN 角色' : '暂无死信（EXHAUSTED）' }}</template>
        </a-table>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router/auto'
import { getRole } from '@/utils/token'
import {
  fetchExhaustedOutbox,
  replayExhaustedOutbox,
  cleanSentOutbox,
  type OutboxEventDTO,
} from '@/api/client'

const router = useRouter()
const role = computed(() => getRole() || '')
const admin = computed(() => role.value === 'ADMIN')

function go(p: string) { router.push(p) }

// ===== Outbox 死信看板 =====
const exhaustedRows = ref<OutboxEventDTO[]>([])
const outboxLoading = ref(false)
const replayId = ref<number | null>(null)
const cleanLoading = ref(false)
const cleanDays = ref(7)
const outboxInfo = ref('')
const outboxError = ref('')

const outboxColumns = [
  { title: 'id', dataIndex: 'id', key: 'id', width: 70 },
  { title: 'eventId', dataIndex: 'eventId', key: 'eventId', width: 140 },
  { title: 'topic', dataIndex: 'topic', key: 'topic', width: 150 },
  { title: 'tag', dataIndex: 'tag', key: 'tag', width: 90 },
  { title: 'payload', dataIndex: 'payload', key: 'payload' },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '重试次数', dataIndex: 'retryCount', key: 'retryCount', width: 90 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', dataIndex: 'action', key: 'action', width: 90 },
]

function truncate(s: unknown, n: number): string {
  if (s == null) return '—'
  const str = String(s)
  return str.length > n ? str.slice(0, n) + '…' : str
}

async function loadOutbox() {
  if (!admin.value || outboxLoading.value) return
  outboxLoading.value = true
  outboxError.value = ''
  try {
    const res = await fetchExhaustedOutbox(200)
    exhaustedRows.value = res.data || []
    outboxInfo.value = `共 ${exhaustedRows.value.length} 条死信`
  } catch (e: any) {
    outboxError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载死信失败')
    exhaustedRows.value = []
  } finally {
    outboxLoading.value = false
  }
}

async function onReplay(record: OutboxEventDTO) {
  if (replayId.value !== null) return
  replayId.value = record.id
  outboxError.value = ''
  try {
    const res = await replayExhaustedOutbox(record.id)
    outboxInfo.value = `死信 #${record.id} 已重放 ${typeof res.data === 'number' ? res.data : 0} 行`
    await loadOutbox()
  } catch (e: any) {
    outboxError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '重放失败')
  } finally {
    replayId.value = null
  }
}

async function onCleanSent() {
  if (!admin.value || cleanLoading.value) return
  cleanLoading.value = true
  outboxError.value = ''
  try {
    const res = await cleanSentOutbox(cleanDays.value)
    outboxInfo.value = `已清理 ${typeof res.data === 'number' ? res.data : 0} 条 ${cleanDays.value} 天前已投递记录`
    await loadOutbox()
  } catch (e: any) {
    outboxError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '清理失败')
  } finally {
    cleanLoading.value = false
  }
}

onMounted(() => {
  if (admin.value) {
    loadOutbox()
  }
})
</script>