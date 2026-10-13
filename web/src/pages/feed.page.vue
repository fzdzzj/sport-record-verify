<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="好友动态" class="mb-4">
      <p class="text-gray-600 mb-2">
        GET /record/api/records/feed 分页（auth 会话下 caller 由网关注入）：本人任意可见性记录 +
        好友 PUBLIC/FRIENDS 记录按创建时间倒序；陌生人记录不入 feed（feed ≠ explore）。
        auth 关闭降级态为 PUBLIC 全站时间线。需登录会话（Bearer）。
      </p>
      <a-alert v-if="!loggedIn" type="info" message="未登录：动态需登录会话，当前仅展示空态，不发起请求。请先登录。" class="mb-2" />

      <div class="mb-2">
        <a-button :loading="loading" @click="loadList">刷新</a-button>
        <a-alert v-if="errorMessage" type="error" :message="errorMessage" class="mt-2" />
      </div>

      <a-spin :spinning="loading">
        <div v-if="rows.length" class="space-y-3">
          <a-card v-for="item in rows" :key="item.id" size="small">
            <div class="flex items-center gap-2 mb-2">
              <span class="font-medium">{{ authorName(item) }}</span>
              <a-tag v-if="item.visibility === 2" color="red">仅自己</a-tag>
              <a-tag v-else-if="item.visibility === 1" color="blue">好友可见</a-tag>
              <span v-else class="text-xs text-gray-500">公开</span>
            </div>
            <div class="flex items-center gap-4 text-sm">
              <span>{{ sportTypeLabel(item.sportType) }}</span>
              <span>{{ (Number(item.distance) || 0).toFixed(2) }} km</span>
              <span class="text-gray-500">{{ statusLabel(item.status) }}</span>
              <span class="text-gray-400 ml-auto">{{ relativeTime(item.createdAt) }}</span>
            </div>
            <div class="mt-2">
              <a-button size="small" @click="goTrack(item.id)">查看详情</a-button>
            </div>
          </a-card>
        </div>
        <div v-else-if="loggedIn && !loading" class="text-gray-500 py-6 text-center">
          {{ rowsMessage }}
        </div>
      </a-spin>

      <div v-if="loggedIn && total > 0" class="mt-4 flex justify-end">
        <a-pagination
          :current="page"
          :page-size="size"
          :total="total"
          show-size-changer
          :page-size-options="['10','20','50']"
          @change="onPageChange"
          @show-size-change="onSizeChange"
        />
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router/auto'
import { getFeed, type FeedItemDTO } from '@/api/client'
import { hasAccessToken } from '@/utils/token'

const router = useRouter()
const loggedIn = computed(() => hasAccessToken())

const rows = ref<FeedItemDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')
const rowsMessage = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)

/** 作者昵称缺失（user-service 软降级空）→ 回退 authorUserId 字符串（R4 fail-safe 口径） */
function authorName(item: FeedItemDTO): string {
  return item.authorNickname || String(item.authorUserId)
}

function sportTypeLabel(code: number): string {
  const m: Record<number, string> = { 1: 'RUNNING', 2: 'CYCLING', 3: 'WALKING' }
  return m[code] || `UNKNOWN(${code})`
}

function statusLabel(code: number): string {
  const m: Record<number, string> = {
    0: '已提交',
    1: '校验中',
    2: '通过',
    3: '拒绝',
    4: '申诉中',
    5: '复审通过',
    6: '维持拒绝',
    7: '人工审核',
  }
  return m[code] || `UNKNOWN(${code})`
}

/** 相对时间：X 分钟/小时/天前，超 7 天显示日期；缺省显示 — */
function relativeTime(iso?: string): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return iso
  const diffMs = Date.now() - t
  const min = Math.floor(diffMs / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day < 7) return `${day} 天前`
  return iso.slice(0, 10)
}

function goTrack(id: number) {
  router.push({ path: '/record-detail', query: { id: String(id) } })
}

async function loadList() {
  if (!loggedIn.value) return
  loading.value = true
  errorMessage.value = ''
  rowsMessage.value = ''
  try {
    const res = await getFeed(page.value, size.value)
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
    // 后端按 createdAt 倒序；页内兜底排序
    rows.value = [...rows.value].sort((a, b) =>
      String(b.createdAt).localeCompare(String(a.createdAt)),
    )
    if (!rows.value.length) rowsMessage.value = '暂无动态（没有本人或好友的可见记录）。'
  } catch (e: any) {
    rows.value = []
    errorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载动态失败')
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number, s: number) {
  page.value = p
  size.value = s
  loadList()
}

function onSizeChange(_c: number, s: number) {
  size.value = s
  page.value = 1
  loadList()
}

onMounted(loadList)
</script>
