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
      <p class="mt-2 text-sm text-gray-600">登录后可在下方查看服务端持久化的「我的记录」列表（GET /record/api/records/my，支持过滤与分页）；提交页的 sessionStorage 仅是本会话内的快捷入口。</p>
      <a-alert class="mt-4" type="info" show-icon message="自助改密（TASK-208）：修改密码后全量吊销该账号 refresh，需重新登录（入口见下）。" />
      <div class="mt-4">
        <a-button type="primary" @click="go('/record-submit')">开始：提交样例轨迹</a-button>
        <a-button class="ml-2" @click="go('/leaderboard')">查看榜单</a-button>
        <a-button class="ml-2" @click="go('/settings')">修改密码</a-button>
      </div>
    </a-card>

    <template v-if="authed">
      <!-- 登录态个人统计仪表盘（TASK-211；统计卡与每日里程 TASK-231 改由 /my/stats 全量聚合，与列表翻页解耦） -->
      <a-card title="我的记录 / 个人统计" class="mt-4">
        <div v-if="loading" class="py-4 text-gray-500">加载中…</div>
        <a-alert v-else-if="loadError" type="error" :message="loadError" />
        <template v-else>
          <!-- 统计卡与近 30 天里程（TASK-232 解耦：仅初次加载/删除后刷新，翻页与筛选不触发） -->
          <a-alert v-if="statsError" class="mb-4" type="warning" show-icon :message="statsError" />
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
          <div v-if="hasDailyActivity" class="flex items-end h-24 gap-0.5 border-b border-gray-200 mb-4" aria-hidden="true">
            <div
              v-for="bar in dailyBars"
              :key="bar.iso"
              :style="{ height: bar.pct + '%', minHeight: bar.distance > 0 ? '2px' : '1px' }"
              class="flex-1 bg-blue-400 rounded-t"
              :title="`${bar.iso}: ${bar.distance}`"
            ></div>
          </div>
          <a-empty v-else class="mb-4" description="近 30 天暂无运动记录，开始记录第一次运动吧！">
            <a-button type="primary" @click="go('/record-submit')">去提交运动记录 &rarr;</a-button>
          </a-empty>

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
                      {{ g.metric === 0 ? '距离' : '次数' }}：{{ formatGoalValue(g.achievedValue, g.metric) }} / {{ formatGoalValue(g.targetValue, g.metric) }} {{ g.metric === 0 ? 'km' : '次' }}
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
          <!-- 列表加载态独立于统计卡（TASK-232）：首屏/全量刷新时表格区显示占位，
              翻页与筛选只走 tableLoading（刷新中…），不闪统计卡与打卡 -->
          <div v-if="tableLoading && !records.length" class="py-4 text-gray-500">加载中…</div>
          <template v-else>
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
                <a-tag v-else-if="column.key === 'visibility' && record.visibility === 0" color="green">公开</a-tag>
                <a-tag v-else-if="column.key === 'visibility' && record.visibility === 2" color="red">仅自己</a-tag>
                <a-tag v-else-if="column.key === 'visibility' && record.visibility === 1" color="blue">好友可见</a-tag>
                <template v-else-if="column.key === 'actions'">
                  <a @click="goTrack(record.id)">查看轨迹</a>
                  <template v-if="canAppeal(record.status)">
                    <a-divider type="vertical" />
                    <a @click="goVerdict(record.id)">判定/申诉</a>
                  </template>
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

            <div v-if="tableLoading" class="mt-2 text-xs text-gray-400">列表刷新中…</div>

            <div v-if="total > size" class="mt-3 flex justify-end">
              <a-pagination
                :current="page"
                :page-size="size"
                :total="total"
                size="small"
                @change="onPageChange"
              />
            </div>
          </template>

          <p class="mt-3 text-xs text-gray-400">
            分页与过滤基于 GET /record/api/records/my（翻页/筛选只重拉列表）；统计卡与近 30 天里程来自 GET /record/api/records/my/stats（服务端 SQL 全量聚合 + 30 天时间窗，翻页不影响统计）。通过率口径 = PASSED(2) + RE_PASSED(5)。
          </p>
        </template>
      </a-card>

      <!-- 成就勋章墙（TASK-269）：读时结算自动解锁并追溯历史；已解锁高亮 + 佩戴/卸下交互，未解锁置灰 + 进度条 -->
      <a-card title="成就勋章墙" class="mt-4">
        <div v-if="badgesLoading" class="py-4 text-gray-500">加载勋章进度中…</div>
        <a-alert v-else-if="badgesError" type="warning" show-icon :message="badgesError" />
        <div v-else class="grid grid-cols-3 gap-3">
          <div
            v-for="b in badges"
            :key="b.code"
            class="rounded border p-3 text-center"
            :class="b.unlocked ? 'border-amber-400 bg-amber-50/60' : 'border-gray-200 bg-gray-50 opacity-70'"
          >
            <div class="text-3xl" :class="{ 'grayscale opacity-40': !b.unlocked }">{{ b.icon }}</div>
            <div class="mt-1 text-sm font-semibold text-gray-800">{{ b.name }}</div>
            <div class="text-xs text-gray-500 mt-0.5">{{ b.description }}</div>
            <div class="mt-1">
              <a-tag v-if="b.isEquipped" color="gold">佩戴中</a-tag>
              <a-tag v-else-if="b.unlocked" color="green">已解锁</a-tag>
              <a-tag v-else>{{ tierLabel(b.tier) }}</a-tag>
            </div>
            <a-progress
              v-if="!b.unlocked"
              :percent="b.progressPct"
              size="small"
              :format="() => formatBadgeProgress(b)"
            />
            <div v-else class="text-xs text-gray-400 mt-1">{{ formatBadgeTime(b.unlockedAt) }} 解锁</div>
            <div class="mt-2">
              <a-button
                v-if="b.unlocked && !b.isEquipped"
                size="small"
                type="primary"
                :loading="badgeActionLoading"
                @click="handleWear(b)"
              >佩戴</a-button>
              <a-button
                v-if="b.isEquipped"
                size="small"
                :loading="badgeActionLoading"
                @click="handleUnwear"
              >卸下</a-button>
            </div>
          </div>
        </div>
      </a-card>
      <!-- 每周运动智能周报（TASK-274 方向四）：跑量/环比/出勤/击败比例/恢复贴士 + 分享长图海报 -->
      <a-card title="📅 每周运动小结 (Weekly Digest)" class="mt-4">
        <div v-if="digestLoading" class="py-4 text-gray-500">加载周报中…</div>
        <a-alert v-else-if="digestError" type="warning" show-icon :message="digestError" />
        <template v-else-if="digest">
          <div class="flex flex-wrap items-center gap-4 mb-3">
            <div>
              <div class="text-3xl font-semibold text-cyan-600">{{ digestKm }}</div>
              <div class="text-xs text-gray-500 mt-1">
                本周跑量 (km) · {{ digest.weekStartDate }} ~ {{ digest.weekEndDate }}
              </div>
            </div>
            <a-tag :color="growthLabel.color" class="text-sm px-3 py-1">{{ growthLabel.text }}</a-tag>
            <div class="text-sm text-gray-700">
              🏃 {{ digest.workoutCount }} 次 · 🔥 {{ digest.totalCalories }} kcal ·
              最佳配速 {{ digest.fastestPaceFormatted }} /km · 主修 {{ primaryTypeLabel }}
            </div>
          </div>
          <div class="mb-2">
            <div class="flex justify-between text-xs text-gray-500 mb-1">
              <span>出勤 {{ digest.activeDays }} / 7 天</span>
              <span>击败全站 {{ beatPct }}% 跑者</span>
            </div>
            <a-progress
              :percent="activeDaysPct"
              :show-info="false"
              size="small"
              stroke-color="#13c2c2"
            />
          </div>
          <div class="border border-cyan-100 bg-cyan-50/50 rounded p-2 text-xs text-cyan-800 mb-3">
            💡 {{ digest.recoveryAdvice }}
          </div>
          <a-button type="primary" ghost @click="posterOpen = true">查看分享长图</a-button>
        </template>
        <div v-else class="py-4 text-gray-500">周报数据不可用。</div>
      </a-card>

      <!-- 我的跑团（TASK-275）：已入团展示名称/角色/成员数，未入团引导前往跑团广场 -->
      <a-card title="🏃 我的跑团" class="mt-4">
        <div v-if="myClubLoading" class="py-4 text-gray-500">加载跑团信息中…</div>
        <template v-else-if="myClub">
          <div class="flex flex-wrap items-center gap-3">
            <div class="text-2xl font-semibold text-blue-600">{{ myClub.name }}</div>
            <a-tag v-if="myRoleLabel" color="gold">{{ myRoleLabel }}</a-tag>
            <a-tag>{{ myClub.memberCount }} 人</a-tag>
          </div>
          <p class="text-sm text-gray-600 mt-2">{{ myClub.description || '暂无简介' }}</p>
          <div class="mt-3">
            <a-button type="primary" ghost @click="go('/clubs')">进入跑团广场</a-button>
            <a-button class="ml-2" @click="go('/leaderboard')">查看跑团里程榜</a-button>
          </div>
        </template>
        <div v-else class="py-2">
          <p class="text-sm text-gray-500 mb-3">
            还没有加入跑团——拉上好友组建跑团，把个人里程沉淀为团队贡献，共同冲击跑团里程榜！
          </p>
          <a-button type="primary" @click="go('/clubs')">前往跑团广场 &rarr;</a-button>
        </div>
      </a-card>
    </template>

    <a-alert type="info" class="mt-4" message="演示要求：网关+服务 app.auth.enabled=true ；前端始终带 Bearer；不伪造 userId。" />

    <!-- 周报分享长图海报 Modal（TASK-274）：纯 CSS 渐变卡面，可截屏分享 -->
    <a-modal v-model:open="posterOpen" title="每周运动小结 · 分享长图" :footer="null" width="420px">
      <div
        v-if="digest"
        class="rounded-xl p-5 text-white"
        style="background: linear-gradient(135deg, #0e7a7a 0%, #13c2c2 60%, #5cdbd3 100%)"
      >
        <div class="text-sm opacity-90">🏃 每周运动智能周报</div>
        <div class="text-xs opacity-75 mb-4">{{ digest.weekStartDate }} ~ {{ digest.weekEndDate }}</div>
        <div class="text-5xl font-bold leading-none">{{ digestKm }}</div>
        <div class="text-sm opacity-90 mt-1 mb-3">本周跑量 (km)</div>
        <div class="inline-block bg-white/20 rounded-full px-3 py-1 text-sm mb-4">
          {{ growthLabel.text }}
        </div>
        <div class="grid grid-cols-2 gap-3 text-sm">
          <div class="bg-white/15 rounded-lg p-2">
            <div class="text-lg font-semibold">{{ digest.workoutCount }} 次</div>
            <div class="text-xs opacity-80">出勤 {{ digest.activeDays }}/7 天</div>
          </div>
          <div class="bg-white/15 rounded-lg p-2">
            <div class="text-lg font-semibold">{{ digest.totalCalories }} kcal</div>
            <div class="text-xs opacity-80">击败 {{ beatPct }}% 跑者</div>
          </div>
        </div>
        <div class="bg-white/15 rounded-lg p-3 mt-3 text-xs leading-5">
          💡 {{ digest.recoveryAdvice }}
        </div>
        <div class="text-center text-xs opacity-75 mt-4">
          运动记录校验平台 · 每周运动小结 · 主修 {{ primaryTypeLabel }}
        </div>
      </div>
      <p class="mt-2 text-xs text-gray-400">
        长图由前端样式渲染（纯 CSS/SVG，零新依赖），可截屏保存或分享到社交平台。
      </p>
    </a-modal>

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
  getMyBadges,
  getMyClub,
  getMyGoals,
  getMyRecords,
  getMyStats,
  getMyStreak,
  getWeeklyDigest,
  getUserProfile,
  unwearBadge,
  upsertGoal,
  wearBadge,
  type BadgeDTO,
  type ClubDTO,
  type GoalDTO,
  type MyRecordsFilterParams,
  type MyStatsDTO,
  type MyStreakDTO,
  type RecordSummaryDTO,
  type WeeklyDigestDTO,
} from '@/api/client'

import { message } from 'ant-design-vue'
import { hasAccessToken } from '@/utils/token'

const router = useRouter()
function go(p: string) { router.push(p) }
function goLogin() { router.push('/login') }
/** 记录轨迹查看入口：recordId 经路由 query 传给 record-detail 页 */
function goTrack(id: number) { router.push({ path: '/record-detail', query: { id: String(id) } }) }

/** 可判定/申诉状态（TASK-237）：校验中(1)/拒绝(3)/维持拒绝(6)/人工审核(7) → 操作列提供直达链接 */
function canAppeal(status: number): boolean {
  return status === 1 || status === 3 || status === 6 || status === 7
}

/** 判定/申诉直达（TASK-237）：带 recordId 跳 /verdict（对齐 verdict 页 route.query.id 范式） */
function goVerdict(id: number) { router.push({ path: '/verdict', query: { id: String(id) } }) }

/** 登录态判定：仅依赖本地 access token 是否存在（取值口径同 auth 会话 util） */
const authed = ref(hasAccessToken())

const records = ref<RecordSummaryDTO[]>([])
/** 首屏/全量刷新闸门：仅初次加载与删除后刷新期间为 true（翻页与筛选不置位，避免整页闪烁） */
const loading = ref(false)
/** 表格独立加载态（TASK-232）：翻页/筛选只影响表格区，统计卡与打卡不闪烁 */
const tableLoading = ref(false)
const loadError = ref('')
/** 统计卡区软错误（TASK-232）：统计拉取失败只提示统计区，不回退清空列表与整页 */
const statsError = ref('')

/**
 * 个人核心统计（TASK-231）：来自 GET /record/api/records/my/stats 的服务端全量聚合。
 * 统计卡与每日里程只读此对象，不再从当前页 records 做浏览器端聚合——翻页/过滤不改变统计口径。
 */
const stats = ref<MyStatsDTO | null>(null)

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

/**
 * 成就勋章墙（TASK-269）：来自 GET /record/api/records/my/badges 的服务端读时结算视图，
 * 调用即自动解锁并追溯历史记录；软错误口径与统计卡一致（提示告警，不拖垮整页）。
 */
const badges = ref<BadgeDTO[]>([])
const badgesLoading = ref(false)
const badgesError = ref('')
const badgeActionLoading = ref(false)

async function loadBadges() {
  if (!authed.value) return
  badgesLoading.value = true
  try {
    const res = await getMyBadges()
    badges.value = res.data || []
    badgesError.value = ''
  } catch (e: any) {
    badgesError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '勋章数据加载失败')
  } finally {
    badgesLoading.value = false
  }
}

/** 佩戴勋章（未解锁 400 由服务端拒绝）；成功后重拉勋章墙刷新佩戴态 */
async function handleWear(b: BadgeDTO) {
  badgeActionLoading.value = true
  try {
    await wearBadge(b.code)
    message.success(`已佩戴「${b.name}」`)
    await loadBadges()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '佩戴失败'))
  } finally {
    badgeActionLoading.value = false
  }
}

/** 卸下勋章（幂等全量置 0）；成功后重拉勋章墙 */
async function handleUnwear() {
  badgeActionLoading.value = true
  try {
    await unwearBadge()
    message.success('已卸下佩戴的勋章')
    await loadBadges()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '卸下失败'))
  } finally {
    badgeActionLoading.value = false
  }
}

/** 勋章阶梯中文标签（未解锁卡片展示） */
function tierLabel(tier: string): string {
  const m: Record<string, string> = { BRONZE: '青铜', SILVER: '白银', GOLD: '黄金', PLATINUM: '铂金' }
  return m[tier] || tier
}

/** 勋章进度文案：currentValue / targetValue（公里数或天数，统一两位小数） */
function formatBadgeProgress(b: BadgeDTO): string {
  return `${Number(b.currentValue ?? 0).toFixed(2)} / ${Number(b.targetValue ?? 0).toFixed(2)}`
}

/** 解锁时间展示：ISO 串截断为 YYYY-MM-DD HH:mm */
function formatBadgeTime(iso?: string): string {
  return iso ? String(iso).slice(0, 16).replace('T', ' ') : '—'
}

// ===== 每周运动智能周报（TASK-274 方向四） =====
const digest = ref<WeeklyDigestDTO | null>(null)
const digestLoading = ref(false)
const digestError = ref('')
const posterOpen = ref(false)

/** 周报加载（软错误口径与统计卡一致）：失败只提示卡区，不拖垮仪表盘 */
async function loadDigest() {
  if (!authed.value) return
  digestLoading.value = true
  try {
    const res = await getWeeklyDigest()
    digest.value = res.data || null
    digestError.value = ''
  } catch (e: any) {
    digestError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '周报数据加载失败')
  } finally {
    digestLoading.value = false
  }
}

const digestKm = computed(() =>
  digest.value ? (Number(digest.value.totalDistanceMeters) / 1000).toFixed(2) : '0.00',
)

/** 环比标签：+15.2% ↗ 绿色 / -3.5% ↘ 灰色 / 持平 –；前一周无数据（1.0）呈 +100.0% ↗ */
const growthLabel = computed<{ text: string; color: string }>(() => {
  if (!digest.value) return { text: '--', color: 'default' }
  const rate = Number(digest.value.distanceGrowthRate)
  const pct = (rate * 100).toFixed(1)
  if (rate > 0) return { text: `+${pct}% ↗`, color: 'green' }
  if (rate < 0) return { text: `${pct}% ↘`, color: 'default' }
  return { text: '持平 –', color: 'default' }
})

const activeDaysPct = computed(() =>
  digest.value ? Math.round((Number(digest.value.activeDays) / 7) * 100) : 0,
)

const beatPct = computed(() =>
  digest.value ? Math.round(Number(digest.value.beatRatio) * 100) : 0,
)

const primaryTypeLabel = computed(() => {
  const t = digest.value?.primarySportType
  const m: Record<string, string> = { RUNNING: '跑步', CYCLING: '骑行', WALKING: '健走' }
  return t ? (m[t] || t) : '—'
})

// ===== 我的跑团（TASK-275）：已入团展示名称/角色/成员数，未入团引导前往跑团广场 =====
const myClub = ref<ClubDTO | null>(null)
const myClubLoading = ref(false)
const myRoleLabel = ref('')

/** 我的跑团加载（软错误口径与统计卡一致）：未入团 data 为 null；角色经个人资料 id 与 creatorId 判定 */
async function loadMyClub() {
  if (!authed.value) return
  myClubLoading.value = true
  try {
    const res = await getMyClub()
    myClub.value = res.data || null
    myRoleLabel.value = ''
    if (myClub.value) {
      try {
        const profile = await getUserProfile()
        if (profile.data && myClub.value) {
          myRoleLabel.value = profile.data.id === myClub.value.creatorId ? '团长' : '团员'
        }
      } catch {
        myRoleLabel.value = ''
      }
    }
  } catch {
    myClub.value = null
  } finally {
    myClubLoading.value = false
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

/**
 * 通过口径：PASSED(2) / RE_PASSED(5)（终端态可入榜/可赞）——TASK-231 起由服务端
 * /my/stats 统一裁决（passCount/passRate），前端不再自行判定。
 */

/** 分页与过滤状态（TASK-224） */
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filterSportType = ref<number | undefined>(undefined)
const filterStatus = ref<number | undefined>(undefined)
const dateRange = ref<[string, string] | null>(null)

function onFilterChange() {
  page.value = 1
  loadRecords()
}

function resetFilters() {
  filterSportType.value = undefined
  filterStatus.value = undefined
  dateRange.value = null
  page.value = 1
  loadRecords()
}

function onPageChange(p: number) {
  page.value = p
  loadRecords()
}

/** 统计卡（TASK-231）：直接读服务端全量聚合，未加载/失败时显示零值兜底 */
const totalCount = computed(() => stats.value?.totalCount ?? 0)

const totalDistance = computed(() => Number(stats.value?.totalDistance ?? 0).toFixed(2))

const passRate = computed(() => stats.value?.passRate ?? '0%')

/**
 * 近 30 天每日里程条（纯 CSS 高度比例，无图表依赖）：
 * 数据源为 /my/stats 的 dailyDistances（服务端按自然日聚合，与列表分页无关）；
 * 前端只做「日期窗口对齐 + 最大值为 100% 的高度归一化」，不再遍历 records 聚合。
 */
const dailyBars = computed(() => {
  const byDay = stats.value?.dailyDistances || {}
  const days: { iso: string; label: string; distance: number; pct: number }[] = []
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  for (let i = 29; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    const key = iso(d)
    const raw = Number(byDay[key])
    const distance = Number.isFinite(raw) ? +raw.toFixed(2) : 0
    days.push({ iso: key, label: `${d.getMonth() + 1}/${d.getDate()}`, distance, pct: 0 })
  }
  let max = 0
  for (const d of days) {
    if (d.distance > max) max = d.distance
  }
  for (const d of days) d.pct = max > 0 ? Math.round((d.distance / max) * 100) : 0
  return days
})

/** 近 30 天是否有任何运动记录（TASK-242 B19）：累计总里程为 0 → 渲染空态引导代替细线虚框 */
const hasDailyActivity = computed(() => dailyBars.value.some(b => b.distance > 0))

/**
 * 运动目标值格式化（TASK-242 B19）：metric === 1（次数）消除无意义的 .00 小数，
 * 显示整数；距离维度仍保留两位小数（如 12.50 km）。
 */
function formatGoalValue(value: number, metric: number): string {
  const n = Number(value)
  if (!Number.isFinite(n)) return '0'
  return metric === 1 ? String(Math.round(n)) : n.toFixed(2)
}

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

/** 请求序号守卫（TASK-257 E26）：快速翻页/筛选时，陈旧响应不写数据态/错误态/不清 loading */
let loadSeq = 0

/**
 * 列表加载（TASK-232 解耦）：只负责「我的记录」分页查询与分页状态。
 * 翻页（onPageChange）与过滤变更（onFilterChange / resetFilters）只调用本函数，
 * 不再重复打 /my/stats、streak 与 goals（消除 N-6 的重复聚合与整页清屏）。
 * 过滤后当前页可能越界（如第 3 页筛选后仅剩 1 页）→ 回退到最后一页重查一次。
 */
async function loadRecords() {
  if (!authed.value) return
  const seq = ++loadSeq
  tableLoading.value = true
  loadError.value = ''
  try {
    const res = await fetchRecordsPage()
    if (seq !== loadSeq) return
    const totalRows = (res.data && res.data.total) || 0
    const lastPage = Math.max(1, Math.ceil(totalRows / size.value))
    if (page.value > lastPage) {
      page.value = lastPage
      const fallback = await fetchRecordsPage()
      if (seq !== loadSeq) return
      applyRecordsPage(fallback)
      return
    }
    applyRecordsPage(res)
  } catch (e: any) {
    if (seq !== loadSeq) return
    loadError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载个人记录失败')
    records.value = []
    total.value = 0
  } finally {
    if (seq === loadSeq) {
      tableLoading.value = false
    }
  }
}

/** 按当前分页与过滤条件拉取一页记录（loadRecords 与其越界回退共用） */
function fetchRecordsPage() {
  const filters: MyRecordsFilterParams = {}
  if (filterSportType.value != null) filters.sportType = filterSportType.value
  if (filterStatus.value != null) filters.status = filterStatus.value
  if (dateRange.value && dateRange.value[0]) filters.startDate = dateRange.value[0]
  if (dateRange.value && dateRange.value[1]) filters.endDate = dateRange.value[1]
  return getMyRecords(page.value, size.value, filters)
}

/** 应用一页列表结果（后端已按 createdAt 倒序；前端再做一次降序兜底） */
function applyRecordsPage(res: { data?: { records?: RecordSummaryDTO[]; total?: number } }) {
  records.value = (res.data && res.data.records) || []
  total.value = (res.data && res.data.total) || 0
  records.value = [...records.value].sort((a, b) =>
    String(b.createdAt).localeCompare(String(a.createdAt)),
  )
}

/**
 * 统计卡与每日里程加载（TASK-232 解耦）：独立于列表，只在首屏与删除记录后触发。
 * 失败时保留上一份统计并给出软提示（不清空、不拖垮列表渲染）。
 */
async function loadStats() {
  if (!authed.value) return
  try {
    const statsRes = await getMyStats()
    stats.value = statsRes.data || null
    statsError.value = ''
  } catch {
    statsError.value = '统计数据加载失败，展示的可能不是最新统计。'
  }
}

/** 连续打卡加载（TASK-220/232）：独立链路，失败不拖垮统计卡区（卡内显示 —） */
async function loadStreak() {
  if (!authed.value) return
  try {
    const res = await getMyStreak()
    streak.value = res.data || null
  } catch {
    streak.value = null
  }
}

/**
 * 全量加载（仅首屏与删除记录后调用）：列表 + 统计卡 + 连续打卡 + 运动目标 + 成就勋章墙 + 周报。
 * loading 闸门只在此处持有，翻页/筛选路径不经过它 → 统计卡与打卡不再闪烁。
 */
async function loadData() {
  if (!authed.value) return
  loading.value = true
  try {
    await Promise.all([loadRecords(), loadStats(), loadStreak(), loadGoals(), loadBadges(), loadDigest(), loadMyClub()])
  } finally {
    loading.value = false
  }
}

/** 删除记录后刷新列表与统计（TASK-223/232）：统计口径随删除变化，故走全量加载 */
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
