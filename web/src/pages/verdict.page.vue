<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="判定结果轮询 & 申诉 & 点赞" class="mb-4">
      <p class="text-gray-600 mb-4">GET /record/api/records/{id}/verify-result 。verdict=0 时显示「校验中」并继续轮询（不要显示失败）；1 通过可赞；2 拒绝可申诉。使用 session 中的 recordId。</p>

      <div class="flex gap-2 mb-4">
        <a-input v-model:value="recordId" placeholder="recordId" style="width:180px" />
        <a-button @click="loadFromSession">从会话加载</a-button>
        <a-button type="primary" :loading="polling" @click="startPoll">开始/刷新轮询</a-button>
        <a-button @click="stopPoll">停止轮询</a-button>
      </div>

      <div v-if="verifyResult" class="mb-4 p-3 border rounded">
        <div>recordId: <b>{{ verifyResult.recordId }}</b></div>
        <div>verdict: <b :class="verdictClass">{{ verdictLabel }}</b></div>
        <div v-if="verifyResult.score !== undefined">score: {{ verifyResult.score }}</div>
        <div v-if="verifyResult.checkedAt">checkedAt: {{ verifyResult.checkedAt }}</div>
        <div v-if="verifyResult.ruleHits" class="text-xs mt-1">ruleHits: {{ verifyResult.ruleHits }}</div>
      </div>

      <a-alert v-if="verifying" type="info" message="校验中 (verdict=0)，异步处理中，每 3 秒轮询..." class="mb-4" />
      <a-alert v-if="pollError" type="error" :message="pollError" class="mb-4" />

      <!-- Like section -->
      <a-card v-if="verifyResult && (verifyResult.verdict === 1 || verifyResult.verdict === 5)" title="点赞 (仅通过可赞)" class="mb-4" size="small">
        <div>当前 likeCount: {{ likeInfo ? likeInfo.likeCount : '...' }} ， liked: {{ likeInfo ? likeInfo.liked : '...' }}</div>
        <a-button :loading="likeLoading" @click="toggleLike" class="mt-2">
          {{ likeInfo && likeInfo.liked ? '取消点赞' : '点赞' }}
        </a-button>
        <a-alert v-if="likeError" type="error" :message="likeError" class="mt-2" />
        <div class="text-xs text-gray-500 mt-1">未通过赞会返回 6001 错误（前端展示，不当成功）。</div>
      </a-card>

      <!-- Appeal section -->
      <a-card v-if="verifyResult && verifyResult.verdict === 2" title="申诉 (仅 REJECTED)" size="small">
        <a-textarea v-model:value="appealReason" placeholder="申诉理由（必填，≤500字）" :rows="3" />
        <a-button :loading="appealing" @click="onAppeal" class="mt-2" :disabled="!appealReason.trim()">提交申诉</a-button>
        <a-alert v-if="appealResult" type="success" :message="`申诉已提交 (id: ${appealResult.id || 'n/a'})`" class="mt-2" />
        <a-alert v-if="appealError" type="error" :message="appealError" class="mt-2" />
      </a-card>

      <a-button @click="goSubmit" class="mt-4">返回提交页</a-button>
    </a-card>

    <a-alert type="warning" class="mt-2 text-xs">
      注意：verdict=0 持续轮询显示校验中；判定完成（1/2）后停止或手动停；刷新页面轮询状态丢失（简单实现）。
    </a-alert>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router/auto'
import { getVerifyResult, submitAppeal, likeRecord, unlikeRecord, getRecordLike, type VerificationResultDTO, type LikeDTO } from '@/api/client'

const route = useRoute()
const router = useRouter()

const recordId = ref<string>('')
const verifyResult = ref<VerificationResultDTO | null>(null)
const polling = ref(false)
const pollError = ref('')
const verifying = ref(false)
let pollTimer: number | null = null

const likeInfo = ref<LikeDTO | null>(null)
const likeLoading = ref(false)
const likeError = ref('')

const appealReason = ref('')
const appealing = ref(false)
const appealResult = ref<any>(null)
const appealError = ref('')

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

function loadFromSession() {
  try {
    const raw = sessionStorage.getItem('sports_record_ids')
    const ids: number[] = raw ? JSON.parse(raw) : []
    if (ids.length) {
      recordId.value = String(ids[0])
    }
  } catch {}
  if (route.query.id) {
    recordId.value = String(route.query.id)
  }
}

async function fetchVerify(quiet = false) {
  if (!recordId.value) return
  try {
    const res = await getVerifyResult(recordId.value)
    verifyResult.value = res.data
    verifying.value = res.data.verdict === 0
    pollError.value = ''
    // refresh like if passed
    if (res.data.verdict === 1) {
      await fetchLike(true)
    }
    return res.data.verdict
  } catch (e: any) {
    if (!quiet) pollError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '查询失败')
    verifying.value = false
    return null
  }
}

async function fetchLike(quiet=false) {
  if (!recordId.value) return
  try {
    const res = await getRecordLike(recordId.value)
    likeInfo.value = res.data
    likeError.value = ''
  } catch (e: any) {
    if (!quiet) likeError.value = e.code ? `[${e.code}] ${e.message}` : e.message
  }
}

async function toggleLike() {
  if (!recordId.value || likeLoading.value) return
  likeLoading.value = true
  likeError.value = ''
  try {
    const isLiked = likeInfo.value?.liked
    if (isLiked) {
      await unlikeRecord(recordId.value)
    } else {
      await likeRecord(recordId.value)
    }
    await fetchLike()
  } catch (e: any) {
    const code = e.code
    if (code === 6001) {
      likeError.value = '[6001] 未通过校验的记录不可点赞'
    } else {
      likeError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
    }
    // do not treat error as success
  } finally {
    likeLoading.value = false
  }
}

async function onAppeal() {
  if (!recordId.value || !appealReason.value.trim() || appealing.value) return
  appealing.value = true
  appealError.value = ''
  appealResult.value = null
  try {
    const res = await submitAppeal(recordId.value, appealReason.value.trim())
    appealResult.value = res.data
    // refetch verify
    await fetchVerify(true)
  } catch (e: any) {
    appealError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '申诉失败')
  } finally {
    appealing.value = false
  }
}

function startPoll() {
  stopPoll()
  polling.value = true
  pollError.value = ''
  fetchVerify()
  pollTimer = window.setInterval(async () => {
    const v = await fetchVerify(true)
    if (v !== 0 && v !== null) {
      // reached final, keep polling flag
    }
  }, 3000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

function goSubmit() {
  router.push('/record-submit')
}

onMounted(() => {
  loadFromSession()
  if (recordId.value) {
    fetchVerify()
    fetchLike(true)
  }
})

onUnmounted(() => {
  stopPoll()
})
</script>

