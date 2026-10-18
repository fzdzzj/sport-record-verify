<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="记录轨迹查看（SVG 投影）" class="mb-4">
      <p class="text-gray-600 mb-4">
        GET /record/api/records/{id}/points（分页 size=500，超 500 点自动循环拉取至全量，上限 20000 点）。归属由服务端校验（auth 开启时本人记录才可查，
        非本人 → 403/1002 权限错误态）。轨迹用局部等距等比投影（cos 纬度修正）到 SVG viewBox（非真实地图，仅示意形状）。
      </p>

      <div class="flex gap-2 mb-4">
        <a-input v-model:value="recordId" placeholder="recordId" style="width:180px" />
        <a-button type="primary" :loading="loading" @click="load">加载轨迹</a-button>
      </div>

      <a-alert
        v-if="!recordId"
        type="info"
        message="从首页「最近记录」的「查看轨迹」链接进入，或在此输入 recordId 后加载。"
      />

      <!-- 摘要头（仅展示性信息，失败降级为仅轨迹） -->
      <div v-if="recordId && verifyResult" class="mb-4 p-3 border rounded">
        <div>recordId: <b>{{ recordId }}</b></div>
        <div>verdict: <b :class="verdictClass">{{ verdictLabel }}</b></div>
        <div v-if="verifyResult.score !== undefined">score: {{ verifyResult.score }}</div>
        <div v-if="verifyResult.checkedAt">checkedAt: {{ verifyResult.checkedAt }}</div>
        <!-- 拒绝申诉引导（TASK-237）：verdict=2 拒绝 → 一键带参跳 /verdict 查看判定明细并申诉 -->
        <a-alert v-if="verifyResult.verdict === 2" type="warning" show-icon class="mt-2">
          <template #message>
            未通过校验？<a class="text-blue-600 underline" @click="goAppeal">点击查看反作弊判定明细并提交申诉</a>
          </template>
        </a-alert>
      </div>
      <a-alert v-if="summaryDegraded" type="warning" class="mb-4" message="记录摘要不可用（仅展示轨迹）。" />

      <!-- 运动指标卡片（TASK-245 B22）：类型/里程/时长/配速 + 可见性原地切换（仅本人可编辑） -->
      <a-card v-if="detail" size="small" class="mb-4" title="运动指标">
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div class="text-xs text-gray-500">运动类型</div>
            <div class="font-medium">{{ sportTypeLabel(detail.sportType) }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-500">里程</div>
            <div class="font-medium">{{ distanceLabel }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-500">耗时</div>
            <div class="font-medium">{{ durationLabel }}</div>
          </div>
          <div>
            <div class="text-xs text-gray-500">平均配速</div>
            <div class="font-medium">{{ paceLabel }}</div>
          </div>
        </div>
        <div class="mt-3 flex items-center gap-2">
          <span class="text-xs text-gray-500">可见性：</span>
          <a-select
            :value="detail.visibility"
            style="width: 140px"
            size="small"
            :loading="visUpdating"
            :options="visibilityOptions"
            @change="onVisibilityChange"
          />
          <span class="text-xs text-gray-400">选择后即时保存（仅本人可改，越权由服务端 403 拦截）。</span>
          <a-alert v-if="visError" type="error" :message="visError" class="ml-2 text-xs" banner />
        </div>
      </a-card>

      <a-spin :spinning="loading">
        <a-alert v-if="permError" type="error" :message="permErrorMsg" />
        <a-alert v-else-if="loadError" type="error" :message="loadError" />
        <div v-else-if="recordId && !loading && points.length === 0" class="text-gray-500 py-6 text-center">
          该记录没有轨迹点（空态）。
        </div>

        <svg
          v-else-if="points.length"
          :viewBox="`0 0 ${W} ${H}`"
          class="w-full border border-gray-200 rounded"
          style="max-height:480px"
          role="img"
          aria-label="轨迹投影示意图"
        >
          <polyline
            v-if="points.length > 1"
            :points="polylinePoints"
            fill="none"
            stroke="#1677ff"
            stroke-width="2"
          />
          <circle
            v-for="(p, i) in projected"
            :key="i"
            :cx="p.x"
            :cy="p.y"
            :r="i === 0 || i === projected.length - 1 ? 5 : 3"
            :fill="i === 0 ? '#52c41a' : i === projected.length - 1 ? '#ff4d4f' : '#1677ff'"
          >
            <title>{{ pointTitle(validPoints[i]) }}</title>
          </circle>
        </svg>
      </a-spin>

      <p v-if="points.length" class="mt-2 text-xs text-gray-500">
        共 {{ points.length }} 个轨迹点（完整加载）；起点绿、终点红，其余点为蓝，悬停查看 seq/speed/ts。
        坐标经局部等距等比投影（cos 纬度修正），固定留边，非真实地理底图。
      </p>

      <div v-if="points.length" class="mt-2 flex items-center gap-2">
        <a-tooltip :title="likeTip">
          <a-button
            size="small"
            :type="likeInfo?.liked ? 'primary' : 'default'"
            :disabled="likeDisabled"
            :loading="likeLoading"
            @click="toggleLike"
          >
            {{ likeLabel }}
          </a-button>
        </a-tooltip>
        <a-button size="small" @click="exportGpx">导出 GPX</a-button>
      </div>

      <!-- 智能纠偏对比双图层（TASK-274）：灰色虚线原始轨迹 vs 青色实线吸附中线同屏叠加 -->
      <div v-if="points.length" class="mt-4">
        <div class="flex flex-wrap items-center gap-3 mb-2">
          <span class="text-sm font-medium">🤖 智能纠偏 (Track Correction)</span>
          <a-switch v-model:checked="showOriginal" size="small" />
          <span class="text-xs text-gray-500">显示原始对比</span>
        </div>
        <a-spin :spinning="compareLoading">
          <a-alert v-if="compareError" type="warning" :message="compareError" />
          <template v-else-if="comparison">
            <!-- 徽标卡片：算法价值外显 -->
            <div class="inline-flex flex-wrap items-center gap-2 border border-cyan-200 bg-cyan-50/60 rounded px-3 py-2 mb-2 text-sm">
              <span class="font-medium text-cyan-700">
                🤖 智能纠偏：已校正 {{ comparison.correctedCount }} 处漂移点，路网吸附率 {{ matchedRatioPct }}%
              </span>
              <a-tag color="blue">平均偏离 {{ comparison.avgOffRoadDistance }}m</a-tag>
              <a-tag color="orange">最大偏离 {{ comparison.maxOffRoadDistance }}m</a-tag>
              <a-tag>采样 {{ comparison.totalPoints }} 点</a-tag>
            </div>
            <svg
              :viewBox="`0 0 ${W} ${H}`"
              class="w-full border border-gray-200 rounded"
              style="max-height:420px"
              role="img"
              aria-label="纠偏对比双图层示意"
            >
              <polyline
                v-if="showOriginal && projected.length > 1"
                :points="polylinePoints"
                fill="none"
                stroke="#8c8c8c"
                stroke-width="2"
                stroke-dasharray="4 4"
              />
              <polyline
                v-if="projectedSnapped.length > 1"
                :points="snappedPolyline"
                fill="none"
                stroke="#13c2c2"
                stroke-width="3"
              />
              <circle
                v-for="(p, i) in projectedSnapped"
                :key="i"
                :cx="p.x"
                :cy="p.y"
                :r="i === 0 || i === projectedSnapped.length - 1 ? 5 : 2"
                :fill="i === 0 ? '#52c41a' : i === projectedSnapped.length - 1 ? '#ff4d4f' : '#13c2c2'"
              />
            </svg>
            <p class="mt-1 text-xs text-gray-500">
              灰色虚线=原始 GPS 采样轨迹，青色实线=PostGIS 路网中线吸附结果（垂距投影纠偏）；
              绿点起点、红点终点，两图层共用同一投影框。
            </p>
          </template>
        </a-spin>
      </div>

      <!-- 动态回放控制器 Track Player（TASK-274）：播放/暂停/倍速/时间滑块 + 小人循迹 + 浮动配速气泡 -->
      <div v-if="projectedSnapped.length > 1" class="mt-4 border border-gray-200 rounded p-3">
        <div class="text-sm font-medium mb-2">🎬 轨迹回放 (Track Player)</div>
        <svg
          :viewBox="`0 0 ${W} ${H}`"
          class="w-full border border-gray-100 rounded bg-gray-50/50"
          style="max-height:360px"
          role="img"
          aria-label="轨迹动态回放示意"
        >
          <polyline
            v-if="projectedSnapped.length > 1"
            :points="snappedPolyline"
            fill="none"
            stroke="#d9d9d9"
            stroke-width="2"
          />
          <polyline
            v-if="playedPolyline"
            :points="playedPolyline"
            fill="none"
            stroke="#13c2c2"
            stroke-width="3"
          />
          <g v-if="playerPos" :transform="`translate(${playerPos.x}, ${playerPos.y})`">
            <rect x="-44" y="-64" width="88" height="22" rx="6" fill="#ffffff" stroke="#13c2c2" />
            <text x="0" y="-48" text-anchor="middle" font-size="11" fill="#0e7a7a">
              {{ playerClock }} · {{ playerPaceText }}
            </text>
            <text x="0" y="6" text-anchor="middle" font-size="16">🏃</text>
          </g>
        </svg>
        <div class="flex flex-wrap items-center gap-3 mt-2">
          <a-button size="small" type="primary" @click="togglePlay">
            {{ playing ? '⏸ 暂停' : '▶ 播放' }}
          </a-button>
          <a-radio-group v-model:value="playSpeed" size="small">
            <a-radio-button :value="1">1x</a-radio-button>
            <a-radio-button :value="2">2x</a-radio-button>
            <a-radio-button :value="4">4x</a-radio-button>
          </a-radio-group>
          <a-slider
            v-model:value="progressPct"
            class="flex-1"
            style="min-width:160px"
            :min="0"
            :max="1000"
            :disabled="playerTotalMs <= 0"
            @change="onSeek"
          />
          <span class="text-xs text-gray-500">{{ Math.round(progressPct / 10) }}%</span>
        </div>
        <p class="mt-1 text-xs text-gray-500">
          小人沿纠偏中线按时间轴平滑循迹，气泡实时显示当前时刻与瞬时配速（速度缺失时显示 --）；
          播完自动停在终点，可拖动滑块回跳或切换倍速。
        </p>
      </div>

      <!-- 配速趋势（纯前端，TASK-219：复用已拉取 points；X=累计 km、Y=配速秒/公里倒置快在上；停顿断线） -->
      <div v-if="paceSectionVisible" class="mt-4">
        <div class="mb-1 text-sm font-medium">配速趋势</div>
        <svg
          :viewBox="`0 0 ${PW} ${PH}`"
          class="w-full border border-gray-200 rounded"
          style="max-height:240px"
          role="img"
          aria-label="配速趋势示意图"
        >
          <polyline
            v-for="(seg, si) in paceSegments"
            :key="si"
            :points="seg"
            fill="none"
            stroke="#1677ff"
            stroke-width="2"
          />
          <circle v-for="(g, i) in paceDisplay" :key="i" :cx="g.x" :cy="g.y" :r="3" fill="#1677ff">
            <title>{{ paceTitle(g) }}</title>
          </circle>
          <text :x="PW - 8" :y="16" text-anchor="end" font-size="12" fill="#888">
            快 {{ formatPace(paceMinMax.min) }}
          </text>
          <text :x="PW - 8" :y="PH - 10" text-anchor="end" font-size="12" fill="#888">
            慢 {{ formatPace(paceMinMax.max) }}
          </text>
          <text :x="8" :y="PH - 10" font-size="12" fill="#888">0</text>
          <text :x="PW - 8" :y="PH - 10" text-anchor="end" font-size="12" fill="#888">
            {{ totalKmText }}
          </text>
        </svg>
        <p class="mt-1 text-xs text-gray-500">
          X=累计公里（haversine R=6371km 逐段累加），Y=配速秒/公里（倒置：越快越靠上）；
          停顿点（speed 缺失/为 0，或配速大于 60 分/公里）断线不连。
        </p>
      </div>

      <!-- 公里分段配速（TASK-273）：后端 /splits 切片，任一环节失败无缝降级前端本地切片 -->
      <div v-if="splits && splits.splits.length" class="mt-4">
        <div class="mb-1 text-sm font-medium">📊 公里分段配速 (Kilometer Splits)</div>
        <div class="flex flex-wrap items-center gap-3 mb-2 text-xs text-gray-600">
          <span>总里程 <b>{{ splitsTotalKm }}</b></span>
          <a-tag v-if="fastestSplit" color="green">
            ⚡ 最快 {{ fastestSplit.km }}km {{ fastestSplit.paceFormatted }} /km
          </a-tag>
          <a-tag v-if="slowestSplit" color="orange">
            🐢 最慢 {{ slowestSplit.km }}km {{ slowestSplit.paceFormatted }} /km
          </a-tag>
        </div>
        <div class="border border-gray-200 rounded p-2">
          <div v-for="s in splits.splits" :key="s.km" class="flex items-center gap-2 mb-1">
            <span class="w-16 text-xs text-gray-600 shrink-0">{{ splitLabel(s) }}</span>
            <div class="flex-1 h-3 bg-gray-100 rounded overflow-hidden">
              <div
                class="h-full rounded"
                :style="{ width: barWidthPct(s) + '%', backgroundColor: barColor(s) }"
              ></div>
            </div>
            <span class="w-20 text-xs text-gray-600 text-right shrink-0">{{ formatElapsed(s.elapsedSeconds) }}</span>
            <span class="w-16 text-xs text-gray-600 text-right shrink-0">{{ s.paceFormatted }} /km</span>
            <span class="w-20 text-xs text-gray-500 text-right shrink-0">{{ formatAvgSpeed(s.avgSpeed) }}</span>
          </div>
        </div>
        <p v-if="!hasElevationData" class="mt-1 text-xs text-gray-400">
          该记录暂无海拔数据：轨迹点含海拔（GPX 导入 &lt;ele&gt;）即可查看海拔剖面与累计爬升/下降。
        </p>
        <p v-else class="mt-1 text-xs text-gray-500">
          柱长按满公里耗时相对比例渲染（越快越长）：⚡绿=最快公里、🐢橙=最慢公里、末尾余程灰。
        </p>
      </div>

      <!-- 海拔剖面（TASK-273）：数据含海拔（hasElevationData 或点位带 ele）时的高度折线与爬升/下降 -->
      <div v-if="hasElevationData && elePoints.length > 1" class="mt-4">
        <div class="mb-1 text-sm font-medium">⛰️ 海拔剖面 (Elevation Profile)</div>
        <div class="flex flex-wrap items-center gap-4 mb-2 text-xs text-gray-600">
          <span>累计爬升 <b class="text-green-600">+{{ splits?.totalElevationGain ?? 0 }} m</b></span>
          <span>累计下降 <b class="text-orange-500">-{{ splits?.totalElevationLoss ?? 0 }} m</b></span>
          <span>最高海拔 <b>{{ eleMinMax.max }} m</b></span>
          <span>最低海拔 <b>{{ eleMinMax.min }} m</b></span>
        </div>
        <svg
          :viewBox="`0 0 ${PW} ${PH}`"
          class="w-full border border-gray-200 rounded"
          style="max-height:200px"
          role="img"
          aria-label="海拔剖面示意图"
        >
          <polyline :points="elevationPolyline" fill="none" stroke="#52c41a" stroke-width="2" />
          <text :x="PW - 8" :y="16" text-anchor="end" font-size="12" fill="#888">{{ eleMinMax.max }} m</text>
          <text :x="PW - 8" :y="PH - 10" text-anchor="end" font-size="12" fill="#888">{{ eleMinMax.min }} m</text>
        </svg>
        <p class="mt-1 text-xs text-gray-500">
          X=累计公里，Y=海拔（米，高在上）；累计爬升/下降与分段高差由轨迹点 ele 逐点累加。
        </p>
      </div>

      <!-- 赛段冲刺成果（TASK-272）：挂载期两段式匹配命中的经典赛段，成绩幂等上报后回填 PR/KOM 判定 -->
      <div v-if="segmentEfforts.length" class="mt-4">
        <div class="mb-1 text-sm font-medium">🏁 赛段冲刺成果 (Segment Efforts)</div>
        <div
          v-for="e in segmentEfforts"
          :key="e.segmentId"
          class="flex flex-wrap items-center gap-2 border border-gray-200 rounded p-2 mb-2"
        >
          <span class="font-medium">{{ e.segmentName || `赛段 #${e.segmentId}` }}</span>
          <a-tag v-if="e.isKom" color="gold">👑 KOM（全场第一）</a-tag>
          <a-tag v-else-if="e.isPr" color="green">PR（个人最好）</a-tag>
          <span class="text-gray-600">完赛耗时 {{ formatElapsed(e.elapsedSeconds) }}</span>
          <span class="text-gray-600">均速 {{ formatAvgSpeed(e.avgSpeed) }}</span>
          <a-button size="small" type="link" @click="openSegmentBoard(e)">查看赛段 KOM 榜</a-button>
        </div>
        <p class="text-xs text-gray-500">
          赛段成绩上报为幂等口径：服务端仅在新成绩优于个人最好成绩（PR）时写榜，PR/KOM 以服务端判定回填为准。
        </p>
      </div>
    </a-card>

    <!-- 赛段 KOM 榜弹窗（TASK-272）：耗时升序，rank 1 即擂主（KOM 👑） -->
    <a-modal v-model:open="segmentBoardOpen" :title="boardTitle" :footer="null">
      <a-spin :spinning="boardLoading">
        <a-alert v-if="boardError" type="error" :message="boardError" />
        <a-table
          v-else
          :data-source="segmentBoard"
          :columns="boardColumns"
          row-key="rank"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <span v-if="column.key === 'elapsed'">{{ formatElapsed(record.elapsedSeconds) }}</span>
            <span v-else>{{ record[column.dataIndex] }}</span>
          </template>
        </a-table>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router/auto'
import { message } from 'ant-design-vue'
import {
  getRecordPoints,
  getVerifyResult,
  getRecordLike,
  likeRecord,
  unlikeRecord,
  getRecordDetail,
  updateRecordVisibility,
  getRecordSplits,
  matchSegments,
  getSegmentLeaderboard,
  submitSegmentEffort,
  getTrackComparison,
  type TrackPointDTO,
  type VerificationResultDTO,
  type LikeDTO,
  type RecordDetailDTO,
  type RecordSplitsDTO,
  type KilometerSplitDTO,
  type SegmentEffortDTO,
  type SegmentLeaderboardDTO,
  type TrackComparisonDTO,
} from '@/api/client'
import { generateGpx } from '@/utils/gpx'
import { computePaceSeries, formatPace, isDrawablePace } from '@/utils/pace'
import { computeKilometerSplits } from '@/utils/splits'
import { formatInstantPace, formatPlaybackClock, getPointAtProgress } from '@/utils/track-playback'
import { hasAccessToken } from '@/utils/token'

const route = useRoute()
const router = useRouter()

const recordId = ref<string>('')
const points = ref<TrackPointDTO[]>([])
const verifyResult = ref<VerificationResultDTO | null>(null)
const summaryDegraded = ref(false)
const loading = ref(false)
const loadError = ref('')
const permError = ref(false)

// ===== 运动指标与可见性（TASK-245 B22/B29）=====
const detail = ref<RecordDetailDTO | null>(null)
const visUpdating = ref(false)
const visError = ref('')
/** 可见性中文名（供回读展示与下拉选项文本复用） */
const visibilityOptions = [
  { value: 0, label: '公开' },
  { value: 1, label: '好友可见' },
  { value: 2, label: '仅自己' },
]

function sportTypeLabel(code: number): string {
  const m: Record<number, string> = { 1: '🏃 跑步', 2: '🚴 骑行', 3: '🚶 健走' }
  return m[code] || `UNKNOWN(${code})`
}

const distanceLabel = computed(() => {
  const d = detail.value?.distance
  return d == null ? '—' : `${Number(d).toFixed(2)} km`
})

const durationLabel = computed(() => {
  const s = detail.value?.duration
  if (s == null) return '—'
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}时${m}分${sec}秒`
  if (m > 0) return `${m}分${sec}秒`
  return `${sec}秒`
})

const paceLabel = computed(() => {
  const d = detail.value?.distance
  const s = detail.value?.duration
  if (d == null || !s || Number(d) <= 0) return '—'
  const minPerKm = s / 60 / Number(d)
  const min = Math.floor(minPerKm)
  const sec = Math.round((minPerKm - min) * 60)
  return `${min}'${String(sec).padStart(2, '0')}" /km`
})

/** 加载详情 + 并发拉取判定/点赞态（与 TASK-236 同 Side-effect 组织） */
async function loadDetail() {
  try {
    const res = await getRecordDetail(recordId.value)
    detail.value = res.data
  } catch (e: any) {
    // 详情失败仅降级为「仅展示轨迹」（归属 403 已由 permError 兜底，不重复展示）
    detail.value = null
  }
}

/** 原地切换可见性（TASK-245 B22/B29）：下拉 change 即保存，失败回滚并提示 */
async function onVisibilityChange(value: number) {
  if (visUpdating.value || !detail.value) return
  const prev = detail.value.visibility
  visUpdating.value = true
  visError.value = ''
  detail.value.visibility = value
  try {
    const res = await updateRecordVisibility(recordId.value, value)
    detail.value = res.data
  } catch (e: any) {
    detail.value.visibility = prev
    visError.value =
      e.code === 1002 || e.status === 403
        ? '越权：仅本人可修改可见性'
        : (e.code ? `[${e.code}] ${e.message}` : (e.message || '修改可见性失败'))
  } finally {
    visUpdating.value = false
  }
}

// ===== 点赞交互（TASK-236）：加载时并发调取点赞态，失败静默降级；点击一键切换 =====
const likeInfo = ref<LikeDTO | null>(null)
const likeLoading = ref(false)

const loggedIn = computed(() => hasAccessToken())

/** 防呆：未登录置灰；verdict 已知且非 PASSED 时置灰（verdict=1 覆盖 通过/复审通过 记录） */
const likeDisabled = computed(() => {
  if (!loggedIn.value) return true
  const v = verifyResult.value
  return v !== null && v.verdict !== 1
})

const likeTip = computed(() => {
  if (!loggedIn.value) return '请先登录'
  if (verifyResult.value !== null && verifyResult.value.verdict !== 1) return '未通过校验的记录不可点赞'
  return ''
})

const likeLabel = computed(() => {
  if (!likeInfo.value) return '赞 …'
  return likeInfo.value.liked ? `已赞 (${likeInfo.value.likeCount})` : `赞 (${likeInfo.value.likeCount})`
})

// 固定 viewBox 与留边；局部等距投影：cos(avgLat) 修正经度地面跨度，统一缩放保持真实纵横比。
const W = 800
const H = 400
const PAD = 16

const permErrorMsg = '无权查看该记录轨迹（服务端归属校验为权威，前端不绕过、不做本地身份判断）。'

const verdictLabel = computed(() => {
  const v = verifyResult.value?.verdict
  if (v === 0) return '0 - VERIFYING (校验中)'
  if (v === 1) return '1 - PASSED (通过)'
  if (v === 2) return '2 - REJECTED (拒绝)'
  return String(v)
})
const verdictClass = computed(() => {
  const v = verifyResult.value?.verdict
  return v === 1 ? 'text-green-600' : v === 2 ? 'text-red-600' : 'text-blue-600'
})

/** 拒绝申诉引导（TASK-237）：一键带参跳转 /verdict（与首页操作列同范式，query.id 供 verdict 页装载） */
function goAppeal() {
  router.push({ path: '/verdict', query: { id: recordId.value } })
}

/** 有效数值坐标点集（几何投影与悬停标题共用，过滤非法 lat/lng） */
const validPoints = computed(() =>
  points.value.filter((p) => Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng))),
)

/**
 * 局部等距等比投影（TASK-238）：cos(avgLat) 修正经度每度地面宽度（纬度 1°≈111km、
 * 经度 1°随纬度余弦缩小），统一缩放比 min(innerW/spanX, innerH/spanY) 居中等比渲染，
 * 消除正方形路线压扁、圆形操场压成椭圆的几何拉伸变形。
 * 退化：无有效点返回空数组；全部同坐标（span≈0）时全部居中映射。
 */
const projected = computed(() => {
  const pts = validPoints.value.map((p) => ({ lat: Number(p.lat), lng: Number(p.lng) }))
  if (!pts.length) return [] as { x: number; y: number }[]
  const minLat = Math.min(...pts.map((v) => v.lat))
  const maxLat = Math.max(...pts.map((v) => v.lat))
  const minLng = Math.min(...pts.map((v) => v.lng))
  const maxLng = Math.max(...pts.map((v) => v.lng))
  const avgLat = (minLat + maxLat) / 2
  const cosLat = Math.cos((avgLat * Math.PI) / 180)
  const spanX = (maxLng - minLng) * cosLat
  const spanY = maxLat - minLat
  const innerW = W - PAD * 2
  const innerH = H - PAD * 2
  // 退化：所有点同坐标 → 全部居中映射
  if (spanX <= 1e-9 && spanY <= 1e-9) {
    const cx = PAD + innerW / 2
    const cy = H - PAD - innerH / 2
    return pts.map(() => ({ x: cx, y: cy }))
  }
  const scaleX = spanX > 1e-9 ? innerW / spanX : 0
  const scaleY = spanY > 1e-9 ? innerH / spanY : 0
  const scale = spanX > 1e-9 && spanY > 1e-9 ? Math.min(scaleX, scaleY) : (scaleX || scaleY)
  const drawW = spanX * scale
  const drawH = spanY * scale
  const offsetX = PAD + (innerW - drawW) / 2
  const offsetY = PAD + (innerH - drawH) / 2
  return pts.map((v) => {
    const px = (v.lng - minLng) * cosLat
    const py = v.lat - minLat
    return {
      x: Number((offsetX + px * scale).toFixed(2)),
      y: Number((H - offsetY - py * scale).toFixed(2)),
    }
  })
})

const polylinePoints = computed(() => projected.value.map((p) => `${p.x},${p.y}`).join(' '))

function pointTitle(p: TrackPointDTO): string {
  const speed = p.speed === undefined || p.speed === null ? '-' : p.speed
  return `seq=${p.seq} speed=${speed} ts=${p.ts}`
}

// ===== 智能纠偏对比双图层（TASK-274 方向一） =====
const comparison = ref<TrackComparisonDTO | null>(null)
const compareLoading = ref(false)
const compareError = ref('')
const showOriginal = ref(true)

const matchedRatioPct = computed(() =>
  comparison.value ? Math.round(comparison.value.matchedRatio * 1000) / 10 : 0,
)

/** 轨迹加载后异步调取纠偏对比（POST /mapmatch/match/compare）；失败降级为仅原始轨迹，不阻断主渲染 */
async function loadComparison() {
  comparison.value = null
  compareError.value = ''
  resetPlayback()
  if (!points.value.length) return
  compareLoading.value = true
  try {
    const res = await getTrackComparison(
      points.value.map((p) => ({ seq: p.seq, lat: Number(p.lat), lng: Number(p.lng), ts: p.ts })),
    )
    comparison.value = res.data
  } catch {
    compareError.value = '纠偏对比服务暂不可用，已降级为仅原始轨迹展示。'
  } finally {
    compareLoading.value = false
  }
}

/**
 * 与主轨迹 SVG 同帧的投影器（同一 validPoints bbox / cos 纬度修正 / 等比缩放 /
 * 居中留边，算法与 projected computed 逐字一致），供吸附中线与回放器共用坐标框。
 */
const snapProjector = computed(() => {
  const pts = validPoints.value.map((p) => ({ lat: Number(p.lat), lng: Number(p.lng) }))
  if (!pts.length) return () => ({ x: W / 2, y: H / 2 })
  const minLat = Math.min(...pts.map((v) => v.lat))
  const maxLat = Math.max(...pts.map((v) => v.lat))
  const minLng = Math.min(...pts.map((v) => v.lng))
  const maxLng = Math.max(...pts.map((v) => v.lng))
  const avgLat = (minLat + maxLat) / 2
  const cosLat = Math.cos((avgLat * Math.PI) / 180)
  const spanX = (maxLng - minLng) * cosLat
  const spanY = maxLat - minLat
  const innerW = W - PAD * 2
  const innerH = H - PAD * 2
  if (spanX <= 1e-9 && spanY <= 1e-9) {
    const cx = PAD + innerW / 2
    const cy = H - PAD - innerH / 2
    return () => ({ x: cx, y: cy })
  }
  const scaleX = spanX > 1e-9 ? innerW / spanX : 0
  const scaleY = spanY > 1e-9 ? innerH / spanY : 0
  const scale = spanX > 1e-9 && spanY > 1e-9 ? Math.min(scaleX, scaleY) : (scaleX || scaleY)
  const drawW = spanX * scale
  const drawH = spanY * scale
  const offsetX = PAD + (innerW - drawW) / 2
  const offsetY = PAD + (innerH - drawH) / 2
  return (lat: number, lng: number) => ({
    x: Number((offsetX + (lng - minLng) * cosLat * scale).toFixed(2)),
    y: Number((H - offsetY - (lat - minLat) * scale).toFixed(2)),
  })
})

/** 吸附中线点列（过滤非法坐标），投影到与主轨迹同帧的 SVG 坐标 */
const projectedSnapped = computed(() => {
  const snapped = comparison.value?.snappedPoints || []
  const projector = snapProjector.value
  return snapped
    .filter((p) => Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng)))
    .map((p) => projector(Number(p.lat), Number(p.lng)))
})

const snappedPolyline = computed(() =>
  projectedSnapped.value.map((p) => `${p.x},${p.y}`).join(' '),
)

// ===== 动态回放控制器（Track Player，TASK-274） =====
const playing = ref(false)
const playSpeed = ref<1 | 2 | 4>(1)
/** 进度滑块（0..1000 归一，避免 a-slider 小数步进抖动） */
const progressPct = ref(0)
const playerElapsedMs = ref(0)
let rafId = 0
let lastTick = 0

/** 回放时间轴总长（毫秒）：优先吸附点列首末 ts，回退原始轨迹 */
const playerTotalMs = computed(() => {
  const snapped = comparison.value?.snappedPoints || []
  const first = snapped[0]?.ts
  const last = snapped[snapped.length - 1]?.ts
  if (first != null && last != null && last > first) return last - first
  const pts = validPoints.value
  if (pts.length > 1 && pts[0].ts != null) {
    const t0 = pts[0].ts
    const t1 = pts[pts.length - 1].ts
    if (t1 > t0) return t1 - t0
  }
  return 0
})

/** 回放进度 0..1 */
const playerProgress = computed(() =>
  playerTotalMs.value > 0 ? playerElapsedMs.value / playerTotalMs.value : 0,
)

/** 回放帧：沿吸附中线按里程归一插值取当前点（平滑循迹） */
const playerPos = computed(() => {
  if (projectedSnapped.value.length < 2) return null
  const snapped = (comparison.value?.snappedPoints || []).filter(
    (p) => Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng)),
  )
  const at = getPointAtProgress(
    snapped.map((p) => ({ seq: p.seq, lat: Number(p.lat), lng: Number(p.lng), ts: p.ts ?? 0 })),
    playerProgress.value,
  )
  if (!at) return null
  return snapProjector.value(at.lat, at.lng)
})

/** 气泡：当前时刻（墙钟）+ 瞬时配速（原始轨迹 speed，缺失 --） */
const playerClock = computed(() => {
  const snapped = comparison.value?.snappedPoints || []
  const baseTs = snapped[0]?.ts ?? validPoints.value[0]?.ts ?? Number.NaN
  return formatPlaybackClock(baseTs, playerElapsedMs.value)
})

const playerPaceText = computed(() => {
  const at = getPointAtProgress(validPoints.value, playerProgress.value)
  return formatInstantPace(at?.speed ?? null) + ' /km'
})

/** 已播片段折线（青色高亮：从起点画到当前帧所在线段，提供循迹视觉反馈） */
const playedPolyline = computed(() => {
  const all = projectedSnapped.value
  if (all.length < 2 || !playerPos.value) return ''
  return all.slice(0, Math.max(2, Math.ceil(playerProgress.value * (all.length - 1)) + 1))
    .map((p) => `${p.x},${p.y}`)
    .join(' ')
})

function togglePlay() {
  if (playerTotalMs.value <= 0) return
  playing.value = !playing.value
  if (playing.value) {
    // 播完后再点播放：从头开始
    if (playerElapsedMs.value >= playerTotalMs.value) {
      playerElapsedMs.value = 0
      progressPct.value = 0
    }
    lastTick = performance.now()
    rafId = requestAnimationFrame(tickPlayback)
  } else if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
}

function tickPlayback(now: number) {
  if (!playing.value) return
  const dt = now - lastTick
  lastTick = now
  const total = playerTotalMs.value
  let next = playerElapsedMs.value + dt * playSpeed.value
  if (next >= total) {
    next = total
    playing.value = false
  }
  playerElapsedMs.value = next
  progressPct.value = Math.round((next / total) * 1000)
  if (playing.value) {
    rafId = requestAnimationFrame(tickPlayback)
  } else {
    rafId = 0
  }
}

/** 滑块回跳：按归一进度重置已播时长（保持播放态连续） */
function onSeek(value: number | [number, number]) {
  const v = Array.isArray(value) ? value[0] : value
  playerElapsedMs.value = (v / 1000) * playerTotalMs.value
  if (playing.value && !rafId) {
    lastTick = performance.now()
    rafId = requestAnimationFrame(tickPlayback)
  }
}

function resetPlayback() {
  playing.value = false
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  playerElapsedMs.value = 0
  progressPct.value = 0
}

onBeforeUnmount(() => {
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
})


// ===== 配速趋势（纯前端，TASK-219）=====
// 折线几何：X=累计 km（haversine 逐段累加），Y=配速秒/公里反转（快在上，配速图惯例）；
// paceSec null 或 >3600 视为停顿 → 断线（paceSegments 分段）；悬停复用 <title> 范式。
const PW = 800
const PH = 200

interface PaceDisplayItem {
  x: number
  y: number
  distKm: number
  paceSec: number | null
  ts: number
}

const paceSeries = computed(() => computePaceSeries(points.value))

const paceHasDrawable = computed(() => paceSeries.value.some((p) => isDrawablePace(p.paceSec)))

// 区块门控：至少两个轨迹点且存在至少一个可绘配速点
const paceSectionVisible = computed(() => points.value.length > 1 && paceHasDrawable.value)

const totalKm = computed(() => {
  const s = paceSeries.value
  return s.length ? s[s.length - 1].distKm : 0
})

const totalKmText = computed(() => `${totalKm.value.toFixed(2)} km`)

// 可绘配速极值：min=最快（秒数最小）、max=最慢
const paceMinMax = computed(() => {
  const vals = paceSeries.value
    .map((p) => p.paceSec)
    .filter((s): s is number => isDrawablePace(s))
  if (!vals.length) return { min: 0, max: 0 }
  return { min: Math.min(...vals), max: Math.max(...vals) }
})

// 与 paceSeries 对齐的几何数组；不可绘点为 null（断线锚）。Y 反转：快（小 paceSec）在上。
const paceGeometry = computed<(PaceDisplayItem | null)[]>(() => {
  const series = paceSeries.value
  if (series.length < 2) return []
  const { max } = paceMinMax.value
  const pRange = max - paceMinMax.value.min
  const total = totalKm.value
  const innerW = PW - PAD * 2
  const innerH = PH - PAD * 2
  return series.map((p, i) => {
    if (!isDrawablePace(p.paceSec)) return null
    const fx = total > 0 ? p.distKm / total : 0.5
    // Y 反转：pace 小（快）→ fy 大 → y 小（靠上）；pace 大（慢）→ 靠下
    const fy = pRange > 0 ? (max - p.paceSec!) / pRange : 0.5
    return {
      x: Number((PAD + fx * innerW).toFixed(2)),
      y: Number((PH - PAD - fy * innerH).toFixed(2)),
      distKm: p.distKm,
      paceSec: p.paceSec,
      ts: points.value[i].ts,
    }
  })
})

// 分段折线：连续可绘点连成一段，停顿点断开（分段 polyline）
const paceSegments = computed(() => {
  const segs: string[] = []
  let cur: string[] = []
  for (const g of paceGeometry.value) {
    if (!g) {
      if (cur.length) {
        segs.push(cur.join(' '))
        cur = []
      }
      continue
    }
    cur.push(`${g.x},${g.y}`)
  }
  if (cur.length) segs.push(cur.join(' '))
  return segs
})

// 悬停圆点（仅可绘点），title 显示 距离+配速+时刻
const paceDisplay = computed(() =>
  paceGeometry.value.filter((g): g is PaceDisplayItem => g !== null),
)

function paceTitle(g: PaceDisplayItem): string {
  return `dist=${g.distKm.toFixed(2)}km pace=${formatPace(g.paceSec)} ts=${g.ts}`
}

// ===== 公里分段配速与海拔剖面（TASK-273）=====
// 优先消费后端 /splits 切片；任一环节失败静默降级为前端本地切片（computeKilometerSplits 同算法口径），
// 绝不阻塞轨迹与配速主渲染。海拔维度：后端 track_point 暂无海拔列（恒 hasElevationData=false），
// GPX 导入点位带 ele 时由本地切片/点位直接透出海拔剖面。
const splits = ref<RecordSplitsDTO | null>(null)

/** 轨迹加载后异步切片；后端失败 → 纯前端本地切片无缝兜底 */
async function loadSplits() {
  splits.value = null
  if (!points.value.length) return
  try {
    const res = await getRecordSplits(recordId.value)
    splits.value = res.data
  } catch {
    splits.value = computeKilometerSplits(points.value, Number(recordId.value) || 0)
  }
}

const splitsTotalKm = computed(() =>
  splits.value ? `${(splits.value.totalDistanceMeters / 1000).toFixed(2)} km` : '—',
)

function splitOf(km: number | null | undefined): KilometerSplitDTO | null {
  if (km == null || !splits.value) return null
  return splits.value.splits.find((s) => s.km === km) || null
}

const fastestSplit = computed(() => splitOf(splits.value?.fastestKm))
const slowestSplit = computed(() => splitOf(splits.value?.slowestKm))

/** 公里号标签：整公里 1km/2km...，末尾余程 +0.35km 形态 */
function splitLabel(s: KilometerSplitDTO): string {
  return s.distanceMeters >= 1000 ? `${s.km}km` : `+${(s.distanceMeters / 1000).toFixed(2)}km`
}

// 配速柱相对比例基准：满公里段耗时 min/max（零耗时退化段不参与）
const paceRangeOfSplits = computed(() => {
  const vals = (splits.value?.splits || [])
    .filter((s) => s.distanceMeters >= 1000 && s.elapsedSeconds > 0)
    .map((s) => s.elapsedSeconds)
  if (!vals.length) return null
  return { min: Math.min(...vals), max: Math.max(...vals) }
})

/** 柱长：越快越长（最快 100%、最慢 30%、区间外钳制）；余程段灰条固定 60% */
function barWidthPct(s: KilometerSplitDTO): number {
  if (s.distanceMeters < 1000) return 60
  const range = paceRangeOfSplits.value
  if (!range || range.max === range.min) return 100
  const pct = 100 - ((s.elapsedSeconds - range.min) / (range.max - range.min)) * 70
  return Math.round(Math.max(30, Math.min(100, pct)))
}

/** 柱色：⚡绿=最快、🐢橙=最慢、末尾余程灰、其余蓝 */
function barColor(s: KilometerSplitDTO): string {
  if (s.distanceMeters < 1000) return '#bfbfbf'
  if (s.isFastest) return '#52c41a'
  if (s.isSlowest) return '#fa8c16'
  return '#1677ff'
}

// ===== 海拔剖面 =====
const elePoints = computed(() =>
  points.value.filter((p) => p.ele != null && Number.isFinite(p.ele)),
)

const hasElevationData = computed(
  () => !!splits.value?.hasElevationData || elePoints.value.length > 0,
)

const eleMinMax = computed(() => {
  const es = elePoints.value.map((p) => p.ele as number)
  if (!es.length) return { min: 0, max: 0 }
  return { min: Math.min(...es), max: Math.max(...es) }
})

/** 高度折线：X=累计公里（复用配速序列口径），Y=海拔归一化（高在上）；全程同海拔退化为中位横线 */
const elevationPolyline = computed(() => {
  const pts = points.value
  const series = paceSeries.value
  const total = totalKm.value
  const { min, max } = eleMinMax.value
  const innerW = PW - PAD * 2
  const innerH = PH - PAD * 2
  const coords: string[] = []
  for (let i = 0; i < pts.length; i++) {
    const ele = pts[i].ele
    if (ele == null || !Number.isFinite(ele)) continue
    const fx = total > 0 ? series[i].distKm / total : 0.5
    const fy = max > min ? (ele - min) / (max - min) : 0.5
    coords.push(`${(PAD + fx * innerW).toFixed(2)},${(PH - PAD - fy * innerH).toFixed(2)}`)
  }
  return coords.join(' ')
})

// ===== 赛段冲刺成果（TASK-272 路段挑战与赛段刷榜）=====
const segmentEfforts = ref<SegmentEffortDTO[]>([])
const segmentBoardOpen = ref(false)
const segmentBoard = ref<SegmentLeaderboardDTO[]>([])
const boardLoading = ref(false)
const boardError = ref('')
const activeSegment = ref<SegmentEffortDTO | null>(null)

const boardTitle = computed(() =>
  `赛段 KOM 榜${activeSegment.value?.segmentName ? '：' + activeSegment.value.segmentName : ''}`,
)

const boardColumns = [
  { title: 'rank', dataIndex: 'rank', key: 'rank' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: '耗时', key: 'elapsed' },
  { title: '配速', dataIndex: 'paceFormatted', key: 'paceFormatted' },
]

/** 秒 → x时x分x秒（与详情耗时口径一致） */
function formatElapsed(seconds?: number): string {
  if (seconds == null || seconds < 0) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.round(seconds % 60)
  if (h > 0) return `${h}时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

/** 米每秒 → km/h（一位小数） */
function formatAvgSpeed(avgSpeed?: number): string {
  if (avgSpeed == null || avgSpeed <= 0) return '—'
  return `${(avgSpeed * 3.6).toFixed(1)} km/h`
}

/**
 * 挂载期赛段匹配（TASK-272）：有轨迹点即异步两段式匹配，命中成果逐条幂等上报
 * （服务端仅在其优于个人最好成绩 PR 时写榜，重放/重访不重复计数）并以响应回填
 * isPr/isKom 判定；任一环节失败静默降级——赛段能力缺失不影响轨迹/配速主渲染。
 */
async function loadSegmentEfforts() {
  segmentEfforts.value = []
  if (!points.value.length) return
  try {
    const res = await matchSegments({ recordId: Number(recordId.value), points: points.value })
    const efforts = res.data || []
    const judged = await Promise.allSettled(
      efforts.map(async (e) => {
        try {
          const r = await submitSegmentEffort(e.segmentId, e)
          return { ...e, ...(r.data || {}) }
        } catch {
          return e // 上报失败仅缺 PR/KOM 标识，成果本身照常展示
        }
      }),
    )
    segmentEfforts.value = judged
      .map((s) => (s.status === 'fulfilled' ? s.value : null))
      .filter((x): x is SegmentEffortDTO => x !== null)
  } catch {
    segmentEfforts.value = []
  }
}

/** 打开该赛段 KOM 榜弹窗（打开时拉取，耗时升序，rank 1 即擂主） */
async function openSegmentBoard(e: SegmentEffortDTO) {
  activeSegment.value = e
  segmentBoardOpen.value = true
  boardLoading.value = true
  boardError.value = ''
  segmentBoard.value = []
  try {
    const res = await getSegmentLeaderboard(e.segmentId)
    segmentBoard.value = res.data || []
  } catch (err: any) {
    boardError.value = err.code ? `[${err.code}] ${err.message}` : (err.message || '赛段榜单加载失败')
  } finally {
    boardLoading.value = false
  }
}

async function load() {
  if (!recordId.value) return
  loading.value = true
  loadError.value = ''
  permError.value = false
  verifyResult.value = null
  summaryDegraded.value = false
  likeInfo.value = null
  try {
    // 全量拉取：首包 size=500；若 total > 已收点数，自动循环分页按批追加直至齐备
    // （防超大死循环，上限 20000 点对齐 DTO 契约；>500 点不再腰斩轨迹与 GPX）
    const firstRes = await getRecordPoints(recordId.value, 1, 500)
    let allPoints = (firstRes.data && firstRes.data.records) || []
    const totalPoints = (firstRes.data && firstRes.data.total) || allPoints.length
    let curPage = 1
    const pageSize = 500
    while (allPoints.length < totalPoints && allPoints.length < 20000) {
      curPage++
      const nextRes = await getRecordPoints(recordId.value, curPage, pageSize)
      const nextBatch = (nextRes.data && nextRes.data.records) || []
      if (!nextBatch.length) break
      allPoints = allPoints.concat(nextBatch)
    }
    points.value = allPoints
    // 赛段匹配（TASK-272）：有轨迹点即异步匹配 + 幂等上报，失败静默降级不阻断主渲染
    void loadSegmentEfforts()
    // 公里分段切片（TASK-273）：有轨迹点即异步调取 /splits，失败降级本地切片，不阻断主渲染
    void loadSplits()
    // 智能纠偏对比（TASK-274）：有轨迹点即异步调取 /match/compare，失败降级纯原始轨迹
    void loadComparison()
    // 摘要头 + 详情指标 + 点赞态：并发调取，任一失败静默降级（403 权限/非通过 6001/摘要缺失均不阻断轨迹渲染）
    await Promise.allSettled([
      getVerifyResult(recordId.value)
        .then((vr) => {
          verifyResult.value = vr.data
        })
        .catch(() => {
          summaryDegraded.value = true
        }),
      loadDetail(),
      getRecordLike(recordId.value)
        .then((res2) => {
          likeInfo.value = res2.data
        })
        .catch(() => {
          // 静默降级：点赞态不可用不影响轨迹与配速渲染
        }),
    ])
  } catch (e: any) {
    points.value = []
    // 服务端归属校验为权威：403/1002 → 权限错误态（不做本地 userId 判断）
    if (e.status === 403 || e.code === 1002) {
      permError.value = true
    } else {
      loadError.value = e.code ? `[${e.code}] ${e.message}` : e.message || '加载轨迹失败'
    }
  } finally {
    loading.value = false
  }
}

// 纯前端导出：generateGpx → Blob(application/gpx+xml) → objectURL → a[download] 点击 → revoke。
// 按钮已按 points.length 门控；真实浏览器下载点击不跑（type-check + build + 单测承载，TASK-213 口径）。
function exportGpx() {
  const xml = generateGpx(points.value, { name: `record-${recordId.value}` })
  const blob = new Blob([xml], { type: 'application/gpx+xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `record-${recordId.value}.gpx`
  a.click()
  URL.revokeObjectURL(url)
}

/** 点赞切换（TASK-236）：与动态流同构，点击一键点赞/取消，成功后以服务端计数同步本地态 */
async function toggleLike() {
  if (likeLoading.value) return
  if (!loggedIn.value) {
    message.warning('请先登录')
    return
  }
  if (likeDisabled.value) {
    message.warning('未通过校验的记录不可点赞')
    return
  }
  likeLoading.value = true
  try {
    const isLiked = likeInfo.value?.liked
    const res = isLiked ? await unlikeRecord(recordId.value) : await likeRecord(recordId.value)
    likeInfo.value = res.data
  } catch (e: any) {
    message.error(
      e.code === 6001
        ? '[6001] 未通过校验的记录不可点赞'
        : e.code
          ? `[${e.code}] ${e.message}`
          : (e.message || '操作失败'),
    )
  } finally {
    likeLoading.value = false
  }
}

onMounted(() => {
  // recordId 取自路由 query（对齐 verdict 页范式）
  if (route.query.id) {
    recordId.value = String(route.query.id)
    load()
  }
})
</script>
