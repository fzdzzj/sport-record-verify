<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="提交记录 - 样例轨迹" class="mb-4">
      <p class="mb-4 text-gray-600">使用内置合法样例轨迹（TrackPointDTO 数组，来自真实路网匹配样例）。提交走 <code>POST /record/api/records</code>。鉴权开启时不携带 userId（网关注入）。</p>

      <a-form layout="vertical">
        <a-form-item label="运动类型 (sportType)">
          <a-select v-model:value="sportType" style="width: 200px">
            <a-select-option :value="1">1 - RUNNING</a-select-option>
            <a-select-option :value="2">2 - CYCLING</a-select-option>
            <a-select-option :value="3">3 - WALKING</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="可见性 (visibility，TASK-221)">
          <a-radio-group v-model:value="visibility">
            <a-radio :value="0">公开</a-radio>
            <a-radio :value="1">好友可见</a-radio>
            <a-radio :value="2">仅自己</a-radio>
          </a-radio-group>
          <div class="mt-1 text-xs text-gray-500">默认「公开」；读取面按可见性授权（PRIVATE 仅自己/FRIENDS 仅好友/PUBLIC 所有人）。</div>
        </a-form-item>

        <a-form-item label="样例轨迹 (内置 10 点)">
          <a-button @click="loadSample" class="mr-2">加载内置样例</a-button>
          <a-button @click="clearPoints">清空</a-button>
          <div class="mt-2 text-xs text-gray-500">points 数量: {{ points.length }} （必须 ≥1）</div>
          <div v-if="points.length" class="mt-1 text-xs text-gray-500">
            推导字段（TASK-234，随提交一并下发）：startTime={{ startTime }} / endTime={{ endTime }} / duration={{ duration }}s / distance={{ distance }}km
          </div>
          <pre v-if="points.length" class="mt-1 text-xs bg-gray-50 p-2 rounded max-h-24 overflow-auto">{{ JSON.stringify(points.slice(0,3), null, 2) }} ... (共 {{ points.length }} 点)</pre>
        </a-form-item>

        <a-form-item label="requestId (幂等键，自动生成)">
          <a-input v-model:value="requestId" placeholder="demo-xxx" />
          <a-button @click="genRequestId" class="mt-1">重新生成</a-button>
        </a-form-item>

        <a-form-item>
          <a-button type="primary" :loading="submitting" @click="onSubmit" :disabled="points.length === 0">提交记录</a-button>
        </a-form-item>
      </a-form>

      <a-alert v-if="submitResult" :type="submitResult.duplicated ? 'warning' : 'success'" class="mt-4">
        <template #message>提交结果 (code=0)</template>
        <template #description>
          <div>recordId: <b>{{ submitResult.recordId }}</b></div>
          <div>status: {{ submitResult.status }} (0=SUBMITTED,1=VERIFYING,...)</div>
          <div>duplicated: {{ submitResult.duplicated }}</div>
          <div v-if="submitResult.message">{{ submitResult.message }}</div>
          <a-button size="small" class="mt-2" @click="saveToSession(submitResult.recordId)">保存到本会话 (sessionStorage)</a-button>
          <a-button size="small" class="mt-2 ml-2" @click="goToVerdict(submitResult.recordId)">去判定页</a-button>
        </template>
      </a-alert>

      <a-alert v-if="submitError" type="error" :message="submitError" class="mt-4" />
    </a-card>

    <a-card title="本会话记录 (sessionStorage 快捷入口；服务端持久化列表见首页「我的记录」)" class="mb-4">
      <div v-if="sessionRecords.length === 0" class="text-gray-500">暂无。提交后点击「保存到本会话」。</div>
      <a-list v-else :data-source="sessionRecords" size="small">
        <template #renderItem="{ item }">
          <a-list-item>
            recordId: <b>{{ item }}</b>
            <a-button size="small" class="ml-2" @click="goToVerdict(item)">查看判定</a-button>
            <a-button size="small" class="ml-1" @click="fetchMyUserId(item)">提取我的 userId</a-button>
            <a-button size="small" danger class="ml-1" @click="removeFromSession(item)">移除</a-button>
          </a-list-item>
        </template>
      </a-list>
      <div class="mt-2 text-xs text-gray-500">说明：本卡片仅是本会话内的提交快捷入口（sessionStorage）。服务端已提供持久化「我的记录」列表与个人统计：GET /record/api/records/my（过滤+分页，TASK-211/224）与 GET /record/api/records/my/stats（TASK-231），入口见首页「我的记录 / 个人统计」。</div>
    </a-card>

    <a-card title="我的 userId (从轨迹点提取)" v-if="myUserId">
      <div>当前会话 myUserId: <b>{{ myUserId }}</b> （可用于好友申请 target）</div>
      <div class="text-xs">（提交记录后通过 /points 提取；存储于 sessionStorage）</div>
    </a-card>

    <a-alert type="info" class="mt-4">
      提示：提交后立即可查 points；判定异步（MQ），用 verdict 轮询。重复提交返回 3004 + 原结果。
    </a-alert>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router/auto'
import { submitRecord, SAMPLE_TRACK_POINTS, getRecordPoints, type RecordSubmitDTO, type RecordSubmitResultDTO, type TrackPointDTO } from '@/api/client'

const router = useRouter()

const sportType = ref(1)
const visibility = ref(0)
const points = ref<TrackPointDTO[]>([])
const requestId = ref('')
const submitting = ref(false)
const submitResult = ref<RecordSubmitResultDTO | null>(null)
const submitError = ref('')
const sessionRecords = ref<number[]>([])
const myUserId = ref<number | null>(null)

// TASK-234：样例轨迹推导出的关键字段（加载样例时同步提取，随提交一并带齐，
// 与服务端「缺字段时按轨迹首末点/相邻点 Haversine 推导」口径一致）
const startTime = ref('')
const endTime = ref('')
const duration = ref(0)
const distance = ref(0)

function genRequestId() {
  requestId.value = `demo-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function loadSample() {
  points.value = JSON.parse(JSON.stringify(SAMPLE_TRACK_POINTS))
  if (!requestId.value) genRequestId()
  // TASK-234：同步提取首末点时间（UTC 墙钟，与服务端推导口径一致）、时长与 Haversine 距离
  const pts = points.value
  const first = pts[0]
  const last = pts[pts.length - 1]
  startTime.value = isoFromTs(first.ts)
  endTime.value = isoFromTs(last.ts)
  duration.value = Math.floor((last.ts - first.ts) / 1000)
  distance.value = Math.round(sumHaversineKm(pts) * 100) / 100
}

function clearPoints() {
  points.value = []
}

/** 毫秒 ts → "YYYY-MM-DDTHH:mm:ss"（UTC 墙钟，与服务端推导同口径） */
function isoFromTs(ts: number): string {
  const d = new Date(ts)
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(d.getUTCDate()).padStart(2, '0')
  const hh = String(d.getUTCHours()).padStart(2, '0')
  const mi = String(d.getUTCMinutes()).padStart(2, '0')
  const ss = String(d.getUTCSeconds()).padStart(2, '0')
  return `${d.getUTCFullYear()}-${mm}-${dd}T${hh}:${mi}:${ss}`
}

/** 相邻点 Haversine 累加距离（公里；公式与常量同 verify GeoUtils/服务端推导） */
function haversineKm(a: TrackPointDTO, b: TrackPointDTO): number {
  const R = 6371000
  const rad = (x: number) => (x * Math.PI) / 180
  const dLat = rad(b.lat - a.lat)
  const dLng = rad(b.lng - a.lng)
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2
  return (2 * R * Math.asin(Math.sqrt(s))) / 1000
}

function sumHaversineKm(pts: TrackPointDTO[]): number {
  let km = 0
  for (let i = 1; i < pts.length; i++) {
    km += haversineKm(pts[i - 1], pts[i])
  }
  return km
}

async function onSubmit() {
  submitting.value = true
  submitError.value = ''
  submitResult.value = null
  try {
    const dto = {
      requestId: requestId.value,
      sportType: sportType.value,
      visibility: visibility.value,
      // TASK-234：带齐推导字段，服务端不再因缺失而留 NULL 破窗
      startTime: startTime.value || undefined,
      endTime: endTime.value || undefined,
      distance: distance.value || undefined,
      duration: duration.value || undefined,
      points: points.value
    } as RecordSubmitDTO
    const res = await submitRecord(dto)
    submitResult.value = res.data
    // auto save to session
    if (res.data?.recordId) {
      saveToSession(res.data.recordId, false)
    }
  } catch (e: any) {
    submitError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '提交失败')
    if (e.code === 3004 && e.data) {
      submitResult.value = e.data
    }
  } finally {
    submitting.value = false
  }
}

const SESSION_KEY = 'sports_record_ids'
const USERID_KEY = 'sports_my_user_id'

function loadSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    sessionRecords.value = raw ? JSON.parse(raw) : []
    const uidRaw = sessionStorage.getItem(USERID_KEY)
    if (uidRaw) myUserId.value = Number(uidRaw)
  } catch {}
}

function saveToSession(id: number, showAlert = true) {
  if (!sessionRecords.value.includes(id)) {
    sessionRecords.value.unshift(id)
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(sessionRecords.value)) } catch {}
  }
  if (showAlert) {
    // simple notice
    console.log('saved', id)
  }
}

function removeFromSession(id: number) {
  sessionRecords.value = sessionRecords.value.filter(x => x !== id)
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(sessionRecords.value)) } catch {}
}

function goToVerdict(id: number) {
  router.push({ path: '/verdict', query: { id: String(id) } })
}

async function fetchMyUserId(recordId: number) {
  try {
    const res = await getRecordPoints(recordId, 1, 1)
    const first = res.data?.records?.[0]
    if (first?.userId) {
      myUserId.value = first.userId
      sessionStorage.setItem(USERID_KEY, String(first.userId))
      alert(`提取到 myUserId: ${first.userId} (已存 session)`)
    } else {
      alert('未取到 userId (点可能未落或分页)')
    }
  } catch (e: any) {
    alert('提取失败: ' + (e.message || e.code))
  }
}

onMounted(() => {
  genRequestId()
  loadSample()
  loadSession()
})
</script>

