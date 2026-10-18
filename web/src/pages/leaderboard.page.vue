<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="榜单 - 总榜 / 好友榜" class="mb-4">
      <p class="text-gray-600 mb-4">GET /leaderboard/api/leaderboard?type=overall|friend&amp;size=...（ZSet 总/好友榜）；下方 今日/本周/本月 走 GET /leaderboard/api/leaderboard/range（settled_at 半开窗口聚合 ACTIVE 贡献，周=ISO 周一、月=本月 1 号，end=今天）。rank 从 1 开始，distance 累计 pass 里程。</p>

      <div class="mb-4">
        <a-radio-group v-model:value="lbType" @change="onLbTypeChange">
          <a-radio-button value="overall">总榜 (overall)</a-radio-button>
          <a-radio-button value="friend">好友榜 (friend)</a-radio-button>
          <a-radio-button value="segment">🏁 赛段 KOM 榜</a-radio-button>
        </a-radio-group>
        <span class="mx-2 text-gray-400">|</span>
        <a-radio-group v-model:value="lbRange" @change="onLbRangeChange">
          <a-radio-button value="day">今日</a-radio-button>
          <a-radio-button value="week">本周</a-radio-button>
          <a-radio-button value="month">本月</a-radio-button>
        </a-radio-group>
        <a-input-number v-model:value="lbSize" :min="1" :max="100" style="width:80px; margin-left:8px" />
        <a-button @click="loadLb" :loading="loading" class="ml-2">查询</a-button>
      </div>

      <!-- 赛段 KOM 榜（TASK-272）：下拉选择赛段，耗时升序，rank 1 即擂主（KOM 👑） -->
      <div v-if="lbMode === 'segment'" class="mb-3">
        <a-select
          v-model:value="segmentId"
          :options="segmentOptions"
          placeholder="选择赛段"
          style="width: 320px"
          :loading="segmentListLoading"
          @change="loadSegmentBoard"
        />
        <span class="ml-2 text-xs text-gray-400">赛段榜按完赛耗时升序（score = 个人最好 elapsed_seconds）。</span>
      </div>

      <a-table :data-source="lbData" :columns="lbColumns" :loading="loading" row-key="rank" size="small">
        <template #bodyCell="{ column, record }">
          <span v-if="column.key === 'distance'">{{ record.distance }}</span>
          <span v-else-if="column.key === 'elapsed'">{{ formatElapsed(record.elapsedSeconds) }}</span>
          <span v-else>{{ record[column.dataIndex] }}</span>
        </template>
      </a-table>

      <div v-if="lbError" class="mt-2">
        <a-alert type="error" :message="lbError" />
      </div>
      <div class="mt-2 text-xs text-gray-500">好友榜：当前用户无好友或好友无上榜数据时返回空数组。总榜无需登录（但演示用登录会话）。赛段 KOM 榜走 GET /leaderboard/api/leaderboard/segments/{segmentId}，赛段列表走 GET /mapmatch/segments。</div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  getLeaderboard,
  getLeaderboardRange,
  getSegments,
  getSegmentLeaderboard,
  type LeaderboardDTO,
  type SegmentLeaderboardDTO,
} from '@/api/client'

const lbType = ref<'overall' | 'friend' | 'segment'>('overall')
const lbRange = ref<'day' | 'week' | 'month'>('day')
const lbSize = ref(10)
const lbData = ref<(LeaderboardDTO | SegmentLeaderboardDTO)[]>([])
const loading = ref(false)
const lbError = ref('')

// ===== 赛段 KOM 榜（TASK-272）：赛段下拉 + 耗时升序榜 =====
const segmentId = ref<number | undefined>(undefined)
const segmentOptions = ref<{ value: number; label: string }[]>([])
const segmentListLoading = ref(false)
const segmentsLoaded = ref(false)

/**
 * 查询分支（TASK-234 死逻辑修复）：TASK-209 引入 lbRange 时分支误写在 lbType 上
 * （'overall'|'friend' 封闭联合恒真 → getLeaderboardRange 分支永远不可达）。
 * 改为「最后一次点选的控制项获胜」：今日/本周/本月 → getLeaderboardRange（period 视图），
 * 总榜/好友榜 → getLeaderboard（ZSet 视图），TASK-272 增补赛段榜 → getSegmentLeaderboard
 * （耗时升序视图），三组按钮均真实生效、无缝协调。
 */
const lbMode = ref<'range' | 'zset' | 'segment'>('range')

function onLbRangeChange() {
  lbMode.value = 'range'
  loadLb()
}

function onLbTypeChange() {
  if (lbType.value === 'segment') {
    lbMode.value = 'segment'
    ensureSegments().then(() => loadSegmentBoard())
    return
  }
  lbMode.value = 'zset'
  loadLb()
}

/** 里程视图列（总榜/好友榜/今日周月）与赛段视图列（耗时升序 + 配速）按模式切换 */
const mileageColumns = [
  { title: 'rank', dataIndex: 'rank', key: 'rank' },
  { title: 'userId', dataIndex: 'userId', key: 'userId' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: 'distance (km)', dataIndex: 'distance', key: 'distance' },
]

const segmentColumns = [
  { title: 'rank', dataIndex: 'rank', key: 'rank' },
  { title: 'userId', dataIndex: 'userId', key: 'userId' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: 'elapsed', key: 'elapsed' },
  { title: 'pace', dataIndex: 'paceFormatted', key: 'paceFormatted' },
]

const lbColumns = computed(() => (lbMode.value === 'segment' ? segmentColumns : mileageColumns))

/** 秒 → x时x分x秒（与记录详情耗时口径一致） */
function formatElapsed(seconds?: number): string {
  if (seconds == null || seconds < 0) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.round(seconds % 60)
  if (h > 0) return `${h}时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

/** 赛段下拉懒加载（首次切到赛段榜才拉取；失败提示且不缓存空态，下次切换重试） */
async function ensureSegments() {
  if (segmentsLoaded.value) return
  segmentListLoading.value = true
  try {
    const res = await getSegments()
    segmentOptions.value = (res.data || []).map((s) => ({
      value: s.id,
      label: `${s.name}（${(s.distanceMeters / 1000).toFixed(1)} km）`,
    }))
    segmentsLoaded.value = true
    if (segmentId.value == null && segmentOptions.value.length) {
      segmentId.value = segmentOptions.value[0].value
    }
  } catch (e: any) {
    lbError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '赛段列表加载失败')
  } finally {
    segmentListLoading.value = false
  }
}

/** ISO 周一（周的第一天）起点；周/月视图 end 恒为今天（进行中周期可见） */
function rangeStart(): string {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  const d = now.getDate()
  const day = new Date(y, m, d)
  const monday = new Date(day)
  monday.setDate(day.getDate() - ((day.getDay() + 6) % 7))
  const first = new Date(y, m, 1)
  if (lbRange.value === 'week') {
    return iso(monday)
  }
  if (lbRange.value === 'month') {
    return iso(first)
  }
  return iso(now)
}

function iso(date: Date): string {
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${mm}-${dd}`
}

/** 请求序号守卫（TASK-257 E26）：快速切换类型/模式/条数时，陈旧响应不写数据态/错误态/不清 loading */
let loadSeq = 0

async function loadLb() {
  if (lbMode.value === 'segment') {
    // 查询按钮在赛段模式下路由到赛段榜加载（lbType='segment' 不进入 ZSet 视图）
    await loadSegmentBoard()
    return
  }
  const seq = ++loadSeq
  loading.value = true
  lbError.value = ''
  try {
    if (lbMode.value === 'range') {
      const res = await getLeaderboardRange(rangeStart(), lbSize.value)
      if (seq !== loadSeq) return
      lbData.value = res.data || []
    } else {
      const res = await getLeaderboard(lbType.value as 'overall' | 'friend', lbSize.value)
      if (seq !== loadSeq) return
      lbData.value = res.data || []
    }
  } catch (e: any) {
    if (seq !== loadSeq) return
    lbError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '查询失败')
    lbData.value = []
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

/** 赛段 KOM 榜加载（TASK-272）：与 loadLb 共享请求序号守卫，快速切换赛段/条数陈旧响应丢弃 */
async function loadSegmentBoard() {
  const seq = ++loadSeq
  if (segmentId.value == null) {
    lbData.value = []
    return
  }
  loading.value = true
  lbError.value = ''
  try {
    const res = await getSegmentLeaderboard(segmentId.value, lbSize.value)
    if (seq !== loadSeq) return
    lbData.value = res.data || []
  } catch (e: any) {
    if (seq !== loadSeq) return
    lbError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '赛段榜单加载失败')
    lbData.value = []
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

onMounted(() => {
  loadLb()
})
</script>
