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
            <div class="mt-2 flex items-center gap-2">
              <a-tooltip :title="likeTip(item)">
                <a-button
                  size="small"
                  :type="likeMap[item.id]?.liked ? 'primary' : 'default'"
                  :disabled="likeDisabled(item)"
                  :loading="likeLoadingMap[item.id] === true"
                  @click="toggleLike(item)"
                >
                  {{ likeLabel(item) }}
                </a-button>
              </a-tooltip>
              <a-button size="small" class="ml-auto" @click="goTrack(item.id)">查看详情</a-button>
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
import { message } from 'ant-design-vue'
import { getFeed, getRecordLike, likeRecord, unlikeRecord, type FeedItemDTO } from '@/api/client'
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

// ===== 点赞交互（TASK-236）：per-card 点赞态 Map + loading 锁，列表加载后异步拉取 =====
const likeMap = ref<Record<number, { liked: boolean; likeCount: number }>>({})
const likeLoadingMap = ref<Record<number, boolean>>({})

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

/** 点赞防呆：未登录或记录未通过（status 非 2/5）置灰，消除 6001 破窗 */
function likeDisabled(item: FeedItemDTO): boolean {
  if (!loggedIn.value) return true
  return item.status !== 2 && item.status !== 5
}

function likeTip(item: FeedItemDTO): string {
  if (!loggedIn.value) return '请先登录'
  if (item.status !== 2 && item.status !== 5) return '未通过校验的记录不可点赞'
  return ''
}

function likeLabel(item: FeedItemDTO): string {
  const info = likeMap.value[item.id]
  if (!info) return '赞 …'
  return info.liked ? `已赞 (${info.likeCount})` : `赞 (${info.likeCount})`
}

/** 列表加载完毕后异步拉取当前页可见记录的点赞态；单条失败不影响整页渲染与列表可用性 */
async function loadLikes() {
  const ids = rows.value.map((r) => r.id)
  const results = await Promise.allSettled(ids.map((id) => getRecordLike(id)))
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') {
      likeMap.value[ids[i]] = { liked: r.value.data.liked, likeCount: r.value.data.likeCount }
    }
    // rejected：单条点赞态查询失败静默跳过（容错降级）
  })
}

async function toggleLike(item: FeedItemDTO) {
  if (likeLoadingMap.value[item.id]) return
  if (!loggedIn.value) {
    message.warning('请先登录')
    return
  }
  if (likeDisabled(item)) {
    message.warning('未通过校验的记录不可点赞')
    return
  }
  likeLoadingMap.value[item.id] = true
  try {
    const info = likeMap.value[item.id]
    const res = info?.liked ? await unlikeRecord(item.id) : await likeRecord(item.id)
    likeMap.value[item.id] = { liked: res.data.liked, likeCount: res.data.likeCount }
  } catch (e: any) {
    message.error(
      e.code === 6001
        ? '[6001] 未通过校验的记录不可点赞'
        : e.code
          ? `[${e.code}] ${e.message}`
          : (e.message || '操作失败'),
    )
  } finally {
    likeLoadingMap.value[item.id] = false
  }
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
    loadLikes()
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
