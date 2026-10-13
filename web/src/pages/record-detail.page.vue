<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="记录轨迹查看（SVG 投影）" class="mb-4">
      <p class="text-gray-600 mb-4">
        GET /record/api/records/{id}/points（分页 size=500 单次拉取）。归属由服务端校验（auth 开启时本人记录才可查，
        非本人 → 403/1002 权限错误态）。轨迹用 min-max 归一化投影到 SVG viewBox（非真实地图，仅示意形状）。
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
      </div>
      <a-alert v-if="summaryDegraded" type="warning" class="mb-4" message="记录摘要不可用（仅展示轨迹）。" />

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
            :fill="i === 0 ? '#22c55e' : i === projected.length - 1 ? '#ef4444' : '#1677ff'"
          >
            <title>{{ pointTitle(points[i]) }}</title>
          </circle>
        </svg>
      </a-spin>

      <p v-if="points.length" class="mt-2 text-xs text-gray-500">
        共 {{ points.length }} 个轨迹点；起点绿、终点红，其余点为蓝，悬停查看 seq/speed/ts。
        <span v-if="points.length >= 500">单次拉取上限 500 点，超出部分轨迹未显示（已知限制）。</span>
        坐标经 min-max 归一化投影，固定留边，非真实地理底图。
      </p>

      <div v-if="points.length" class="mt-2">
        <a-button size="small" @click="exportGpx">导出 GPX</a-button>
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
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router/auto'
import {
  getRecordPoints,
  getVerifyResult,
  type TrackPointDTO,
  type VerificationResultDTO,
} from '@/api/client'
import { generateGpx } from '@/utils/gpx'
import { computePaceSeries, formatPace, isDrawablePace } from '@/utils/pace'

const route = useRoute()

const recordId = ref<string>('')
const points = ref<TrackPointDTO[]>([])
const verifyResult = ref<VerificationResultDTO | null>(null)
const summaryDegraded = ref(false)
const loading = ref(false)
const loadError = ref('')
const permError = ref(false)

// 固定 viewBox 与留边；lat/lng 各自 min-max 归一化后按比例铺入内层区。
const W = 800
const H = 480
const PAD = 40

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

/**
 * 归一化投影：lng→x（左西右东），lat→y（翻转使北在上）。
 * 退化：单点或所有点同坐标（range=0）时取 0.5，落内层区正中。
 */
const projected = computed(() => {
  const pts = points.value
  if (!pts.length) return [] as { x: number; y: number }[]
  const lats = pts.map((p) => Number(p.lat))
  const lngs = pts.map((p) => Number(p.lng))
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const latRange = maxLat - minLat
  const lngRange = maxLng - minLng
  const innerW = W - PAD * 2
  const innerH = H - PAD * 2
  return pts.map((p) => {
    const fx = lngRange > 0 ? (Number(p.lng) - minLng) / lngRange : 0.5
    const fy = latRange > 0 ? (Number(p.lat) - minLat) / latRange : 0.5
    return {
      x: Number((PAD + fx * innerW).toFixed(2)),
      y: Number((H - PAD - fy * innerH).toFixed(2)),
    }
  })
})

const polylinePoints = computed(() => projected.value.map((p) => `${p.x},${p.y}`).join(' '))

function pointTitle(p: TrackPointDTO): string {
  const speed = p.speed === undefined || p.speed === null ? '-' : p.speed
  return `seq=${p.seq} speed=${speed} ts=${p.ts}`
}

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

async function load() {
  if (!recordId.value) return
  loading.value = true
  loadError.value = ''
  permError.value = false
  verifyResult.value = null
  summaryDegraded.value = false
  try {
    // size=500 单次拉取（沿用 my-records 同口径；>500 为已知部分轨迹限制）
    const res = await getRecordPoints(recordId.value, 1, 500)
    points.value = (res.data && res.data.records) || []
    // 摘要头：既有 verify-result 端点，仅展示性信息；失败不影响轨迹展示
    try {
      const vr = await getVerifyResult(recordId.value)
      verifyResult.value = vr.data
    } catch {
      summaryDegraded.value = true
    }
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

onMounted(() => {
  // recordId 取自路由 query（对齐 verdict 页范式）
  if (route.query.id) {
    recordId.value = String(route.query.id)
    load()
  }
})
</script>
