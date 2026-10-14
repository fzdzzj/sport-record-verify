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

          <!-- 运动目标与进度（TASK-225） -->
          <div class="mb-4 p-4 rounded border border-gray-200 bg-gray-50/50">
            <div class="flex items-center justify-between mb-3">
              <span class="text-sm font-semibold text-gray-700">运动目标与进度</span>
              <a-button size="small" type="primary" @click="openCreateGoalModal">设置目标</a-button>
            </div>
            <div v-if="goalsLoading" class="py-2 text-gray-400 text-sm">加载目标进度中…</div>
            <div v-else-if="goals.length === 0" class="py-2 text-gray-400 text-sm">
              暂无运动目标，点击「设置目标」制定周/月运动计划。
            </div>
            <div v-else class="space-y-3">
              <div
                v-for="g in goals"
                :key="g.id"
                class="bg-white p-3 rounded border border-gray-100 shadow-xs"
              >
                <div class="flex items-center justify-between text-sm mb-1">
                  <div class="flex items-center gap-2">
                    <a-tag :color="g.periodType === 0 ? 'blue' : 'purple'">
                      {{ g.periodType === 0 ? '周目标' : '月目标' }}
                    </a-tag>
                    <span class="font-medium text-gray-800">
                      {{ g.metric === 0 ? '距离' : '次数' }}：{{ g.achievedValue }} / {{ g.targetValue }} {{ g.metric === 0 ? 'km' : '次' }}
                    </span>
                    <a-tag v-if="g.percent >= 100" color="green">已达成</a-tag>
                  </div>
                  <div class="space-x-2 text-xs">
                    <a @click="openEditGoalModal(g)">编辑</a>
                    <a-divider type="vertical" />
                    <a-popconfirm
                      title="确认删除该运动目标？"
                      ok-text="确认"
                      cancel-text="取消"
                      @confirm="handleDeleteGoal(g.id)"
                    >
                      <a class="text-red-500">删除</a>
                    </a-popconfirm>
                  </div>
                </div>
                <a-progress
                  :percent="g.percent"
                  :status="g.percent >= 100 ? 'success' : 'active'"
                  size="small"
                />
              </div>
            </div>
          </div>

          <div class="mb-3 flex flex-wrap items-center gap-2">
            <span class="text-sm text-gray-600 font-medium">筛选：</span>
            <a-select
              v-model:value="filterSportType"
              placeholder="运动类型"
              allow-clear
              style="width: 120px"
              @change="onFilterChange"
            >
              <a-select-option :value="1">跑步</a-select-option>
              <a-select-option :value="2">骑行</a-select-option>
              <a-select-option :value="3">步行</a-select-option>
            </a-select>

            <a-select
              v-model:value="filterStatus"
              placeholder="审核状态"
              allow-clear
              style="width: 120px"
              @change="onFilterChange"
            >
              <a-select-option :value="0">已提交</a-select-option>
              <a-select-option :value="1">校验中</a-select-option>
              <a-select-option :value="2">通过</a-select-option>
              <a-select-option :value="3">拒绝</a-select-option>
              <a-select-option :value="4">申诉中</a-select-option>
              <a-select-option :value="5">复审通过</a-select-option>
              <a-select-option :value="6">维持拒绝</a-select-option>
              <a-select-option :value="7">人工审核</a-select-option>
            </a-select>

            <a-range-picker
              v-model:value="dateRange"
              value-format="YYYY-MM-DD"
              :placeholder="['开始日期', '结束日期']"
              @change="onFilterChange"
            />

            <a-button @click="resetFilters">重置</a-button>
          </div>

          <div class="mb-1 text-sm text-gray-600">运动记录列表</div>
          <a-table
            v-if="records.length"
            :data-source="records"
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

          <div v-if="total > size" class="mt-3 flex justify-end">
            <a-pagination
              :current="page"
              :page-size="size"
              :total="total"
              size="small"
              @change="onPageChange"
            />
          </div>

          <p class="mt-3 text-xs text-gray-400">
            分页与过滤基于 GET /record/api/records/my；通过率口径 = PASSED(2) + RE_PASSED(5)。
          </p>
        </template>
      </a-card>
    </template>

    <a-alert type="info" class="mt-4" message="演示要求：网关+服务 app.auth.enabled=true ；前端始终带 Bearer；不伪造 userId。" />

    <!-- 运动目标设置/编辑对话框（TASK-225） -->
    <a-modal
      v-model:open="goalModalVisible"
      :title="editingGoalId ? '编辑运动目标' : '设置运动目标'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="goalSaving"
      @ok="handleSaveGoal"
    >
      <a-form layout="vertical" class="mt-4">
        <a-form-item label="周期类型">
          <a-select v-model:value="goalForm.periodType" :disabled="!!editingGoalId">
            <a-select-option :value="0">周目标（周一至周日）</a-select-option>
            <a-select-option :value="1">月目标（自然月）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="指标类型">
          <a-select v-model:value="goalForm.metric" :disabled="!!editingGoalId">
            <a-select-option :value="0">距离 (km)</a-select-option>
            <a-select-option :value="1">次数 (次)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="目标值">
          <a-input-number
            v-model:value="goalForm.targetValue"
            :min="0.1"
            :step="goalForm.metric === 0 ? 1 : 1"
            style="width: 100%"
            placeholder="请输入目标值"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>


<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router/auto'
import {
  delRecord,
  deleteGoal,
  getMyGoals,
  getMyRecords,
  getMyStreak,
  upsertGoal,
  type GoalDTO,
  type MyRecordsFilterParams,
  type MyStreakDTO,
  type RecordSummaryDTO,
} from '@/api/client'

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

/** 运动目标（TASK-225） */
const goals = ref<GoalDTO[]>([])
const goalsLoading = ref(false)
const goalModalVisible = ref(false)
const goalSaving = ref(false)
const editingGoalId = ref<number | null>(null)
const goalForm = ref({
  periodType: 0,
  metric: 0,
  targetValue: 10,
})

async function loadGoals() {
  if (!authed.value) return
  goalsLoading.value = true
  try {
    const res = await getMyGoals()
    goals.value = res.data || []
  } catch {
    goals.value = []
  } finally {
    goalsLoading.value = false
  }
}

function openCreateGoalModal() {
  editingGoalId.value = null
  goalForm.value = { periodType: 0, metric: 0, targetValue: 10 }
  goalModalVisible.value = true
}

function openEditGoalModal(g: GoalDTO) {
  editingGoalId.value = g.id
  goalForm.value = {
    periodType: g.periodType,
    metric: g.metric,
    targetValue: g.targetValue,
  }
  goalModalVisible.value = true
}

async function handleSaveGoal() {
  if (goalForm.value.targetValue == null || goalForm.value.targetValue <= 0) {
    message.warning('目标值必须大于0')
    return
  }
  goalSaving.value = true
  try {
    await upsertGoal({
      periodType: goalForm.value.periodType,
      metric: goalForm.value.metric,
      targetValue: goalForm.value.targetValue,
    })
    message.success('运动目标设置成功')
    goalModalVisible.value = false
    await loadGoals()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '保存失败'))
  } finally {
    goalSaving.value = false
  }
}

async function handleDeleteGoal(id: number) {
  try {
    await deleteGoal(id)
    message.success('运动目标已删除')
    await loadGoals()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '删除失败'))
  }
}

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

/** 分页与过滤状态（TASK-224） */
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filterSportType = ref<number | undefined>(undefined)
const filterStatus = ref<number | undefined>(undefined)
const dateRange = ref<[string, string] | null>(null)

function onFilterChange() {
  page.value = 1
  loadData()
}

function resetFilters() {
  filterSportType.value = undefined
  filterStatus.value = undefined
  dateRange.value = null
  page.value = 1
  loadData()
}

function onPageChange(p: number) {
  page.value = p
  loadData()
}

const totalCount = computed(() => total.value)

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
    const filters: MyRecordsFilterParams = {}
    if (filterSportType.value != null) filters.sportType = filterSportType.value
    if (filterStatus.value != null) filters.status = filterStatus.value
    if (dateRange.value && dateRange.value[0]) filters.startDate = dateRange.value[0]
    if (dateRange.value && dateRange.value[1]) filters.endDate = dateRange.value[1]

    const res = await getMyRecords(page.value, size.value, filters)
    records.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
    // 最近记录由后端 createdAt 倒序返回；若前端发现未排序则按 createdAt 降序兜底
    records.value = [...records.value].sort((a, b) =>
      String(b.createdAt).localeCompare(String(a.createdAt)),
    )
  } catch (e: any) {
    loadError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载个人记录失败')
    records.value = []
    total.value = 0
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

  // 运动目标（TASK-225）：独立拉取
  await loadGoals()
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
