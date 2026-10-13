<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="欢迎 - 运动记录校验业务控制台">
      <p>已实现主演示链路（仅 web 侧）：</p>
      <ul class="list-disc pl-5 mt-2">
        <li>提交记录（内置样例轨迹 + POST /record/api/records）</li>
        <li>判定轮询 + 申诉（verdict=0 轮询中；1 可赞；2 可申诉）</li>
        <li>通过记录点赞（6001 错误展示）</li>
        <li>好友申请/处理/列表</li>
        <li>总榜与好友榜</li>
        <li>我的记录与个人统计（登录态仪表盘）</li>
        <li>记录轨迹查看（纯前端 SVG 投影，最近记录行「查看轨迹」入口）</li>
      </ul>
      <p class="mt-4">使用顶部导航进入各页。需先 <a @click="goLogin">登录</a>（依赖 auth 会话）后可见下方个人统计。</p>
      <p class="mt-2 text-sm text-gray-600">本会话 recordId 存 sessionStorage；刷新或换浏览器不会从服务端恢复（诚实无列表 API）。</p>
      <a-alert class="mt-4" type="info" show-icon message="自助改密（TASK-208）：修改密码后全量吊销该账号 refresh，需重新登录（入口见下）。" />
      <div class="mt-4">
        <a-button type="primary" @click="go('/record-submit')">开始：提交样例轨迹</a-button>
        <a-button class="ml-2" @click="go('/leaderboard')">查看榜单</a-button>
        <a-button class="ml-2" @click="go('/settings')">修改密码</a-button>
      </div>
    </a-card>

    <template v-if="authed">
      <!-- 登录态个人统计仪表盘（TASK-211）：统计自单次 size=500 拉取 -->
      <a-card title="我的记录 / 个人统计" class="mt-4">
        <div v-if="loading" class="py-4 text-gray-500">加载中…</div>
        <a-alert v-else-if="loadError" type="error" :message="loadError" />
        <template v-else>
          <div class="grid grid-cols-4 gap-4 mb-4">
            <div class="rounded border border-gray-200 p-4 text-center">
              <div class="text-2xl font-semibold">{{ totalDistance }}</div>
              <div class="text-xs text-gray-500 mt-1">总里程 (km)</div>
            </div>
            <div class="rounded border border-gray-200 p-4 text-center">
              <div class="text-2xl font-semibold">{{ totalCount }}</div>
              <div class="text-xs text-gray-500 mt-1">记录数</div>
            </div>
            <div class="rounded border border-gray-200 p-4 text-center">
              <div class="text-2xl font-semibold">{{ passRate }}</div>
              <div class="text-xs text-gray-500 mt-1">通过率</div>
            </div>
            <div class="rounded border border-gray-200 p-4 text-center">
              <div class="text-lg font-semibold leading-snug">{{ streakText }}</div>
              <div class="text-xs text-gray-500 mt-1">连续打卡</div>
            </div>
          </div>

          <div class="mb-1 text-sm text-gray-600">近 30 天每日里程（km）</div>
          <div class="flex items-end h-24 gap-0.5 border-b border-gray-200 mb-4" aria-hidden="true">
            <div
              v-for="bar in dailyBars"
              :key="bar.iso"
              :style="{ height: bar.pct + '%', minHeight: bar.distance > 0 ? '2px' : '1px' }"
              class="flex-1 bg-blue-400 rounded-t"
              :title="`${bar.iso}: ${bar.distance}`"
            ></div>
          </div>

          <div class="mb-1 text-sm text-gray-600">最近记录（前 10 条，同一批数据）</div>
          <a-table
            v-if="recent.length"
            :data-source="recent"
            :columns="recentColumns"
            :pagination="false"
            size="small"
            row-key="id"
          >
            <template #bodyCell="{ column, record }">
              <span v-if="column.key === 'sportType'">{{ sportTypeLabel(record.sportType) }}</span>
              <span v-else-if="column.key === 'status'">{{ statusLabel(record.status) }}</span>
              <a-tag v-else-if="column.key === 'visibility' && record.visibility === 2" color="red">仅自己</a-tag>
              <a-tag v-else-if="column.key === 'visibility' && record.visibility === 1" color="blue">好友可见</a-tag>
              <template v-else-if="column.key === 'actions'">
                <a @click="goTrack(record.id)">查看轨迹</a>
                <a-divider type="vertical" />
                <a-popconfirm
                  title="确认删除该运动记录？此操作将永久删除。"
                  ok-text="确认"
                  cancel-text="取消"
                  @confirm="handleDelete(record.id)"
                >
                  <a class="text-red-500">删除</a>
                </a-popconfirm>
              </template>
            </template>
          </a-table>
          <div v-else class="text-gray-500 py-2">暂无运动记录。</div>

          <p class="mt-3 text-xs text-gray-400">
            统计基于单次 size=500 拉取；本人记录超过 500 条时为部分统计（已知限制）。通过率口径 = PASSED(2) + RE_PASSED(5)。
          </p>
        </template>
      </a-card>
    </template>

    <a-alert type="info" class="mt-4" message="演示要求：网关+服务 app.auth.enabled=true ；前端始终带 Bearer；不伪造 userId。" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router/auto'
import { delRecord, getMyRecords, getMyStreak, type MyStreakDTO, type RecordSummaryDTO } from '@/api/client'
import { message } from 'ant-design-vue'
import { hasAccessToken } from '@/utils/token'

const router = useRouter()
function go(p: string) { router.push(p) }
function goLogin() { router.push('/login') }
/** 记录轨迹查看入口：recordId 经路由 query 传给 record-detail 页 */
function goTrack(id: number) { router.push({ path: '/record-detail', query: { id: String(id) } }) }

/** 登录态判定：仅依赖本地 access token 是否存在（取值口径同 auth 会话 util） */
const authed = ref(hasAccessToken())

const records = ref<RecordSummaryDTO[]>([])
const loading = ref(false)
const loadError = ref('')

/** 连续打卡（TASK-220）：current 当前连续天数 / longest 历史最长；未加载或失败显示 — */
const streak = ref<MyStreakDTO | null>(null)

const streakText = computed(() => {
  if (!streak.value) return '—'
  return `当前 ${streak.value.current} 天 · 最长 ${streak.value.longest} 天`
})

const recentColumns = [
  { title: 'id', dataIndex: 'id', key: 'id' },
  { title: '运动类型', dataIndex: 'sportType', key: 'sportType' },
  { title: '距离(km)', dataIndex: 'distance', key: 'distance' },
  { title: '可见性', dataIndex: 'visibility', key: 'visibility' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
  { title: '操作', key: 'actions' },
]

/** 通过口径：isPassed 语义 = PASSED(2) 或 RE_PASSED(5)（终端态可入榜/可赞） */
function isPassed(status: number): boolean {
  return status === 2 || status === 5
}

const totalCount = computed(() => records.value.length)

const totalDistance = computed(() =>
  records.value.reduce((sum, r) => sum + (Number(r.distance) || 0), 0).toFixed(2),
)

const passCount = computed(() => records.value.filter((r) => isPassed(r.status)).length)

const passRate = computed(() => {
  const n = records.value.length
  if (n === 0) return '0%'
  return `${((passCount.value / n) * 100).toFixed(1)}%`
})

/** 近 30 天每日里程条（纯 CSS 高度比例，无图表依赖）；key 取 createdAt 日期部分 YYYY-MM-DD */
const dailyBars = computed(() => {
  const days: { iso: string; label: string; distance: number; pct: number }[] = []
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  for (let i = 29; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    days.push({ iso: iso(d), label: `${d.getMonth() + 1}/${d.getDate()}`, distance: 0, pct: 0 })
  }
  const byDay = new Map<string, number>()
  for (const r of records.value) {
    if (typeof r.createdAt !== 'string') continue
    const k = r.createdAt.slice(0, 10)
    if (!byDay.has(k)) byDay.set(k, 0)
    byDay.set(k, (byDay.get(k) as number) + (Number(r.distance) || 0))
  }
  let max = 0
  for (const d of days) {
    const v = byDay.get(d.iso) || 0
    d.distance = +v.toFixed(2)
    if (d.distance > max) max = d.distance
  }
  for (const d of days) d.pct = max > 0 ? Math.round((d.distance / max) * 100) : 0
  return days
})

function iso(date: Date): string {
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${mm}-${dd}`
}

/** 最近记录：同批数据按后端已排序（createdAt 倒序）取前 10 */
const recent = computed(() => records.value.slice(0, 10))

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

async function loadData() {
  if (!authed.value) return
  loading.value = true
  loadError.value = ''
  try {
    const res = await getMyRecords(1, 500)
    records.value = (res.data && res.data.records) || []
    // 最近记录由后端 createdAt 倒序返回；若前端发现未排序则按 createdAt 降序兜底
    records.value = [...records.value].sort((a, b) =>
      String(b.createdAt).localeCompare(String(a.createdAt)),
    )
  } catch (e: any) {
    loadError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载个人记录失败')
    records.value = []
  } finally {
    loading.value = false
  }

  // 连续打卡（TASK-220）：独立拉取，失败不拖垮统计卡区（卡内显示 —）
  try {
    const res = await getMyStreak()
    streak.value = res.data || null
  } catch {
    streak.value = null
  }
}

/** 删除记录并刷新列表与统计（TASK-223） */
async function handleDelete(id: number) {
  try {
    await delRecord(id)
    message.success('记录删除成功')
    await loadData()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '删除失败'))
  }
}

onMounted(async () => {
  await loadData()
})
</script>
