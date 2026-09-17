<template>
  <div class="max-w-2xl mx-auto">
    <a-card title="探活页 (Probe)" :loading="loading">
      <p class="mb-4 text-gray-600">调用公开探活接口（无需登录）验证代理与响应解析是否正常。</p>
      <p class="mb-2">请求: <code>GET /leaderboard/api/leaderboard?type=overall&size=2</code></p>
      
      <a-button type="primary" @click="runProbe" :loading="loading" class="mb-4">执行探活</a-button>
      
      <a-alert v-if="result" :message="`code: ${result.code}`" :description="result.message" :type="result.code === 0 ? 'success' : 'warning'" show-icon class="mb-4" />
      
      <a-card v-if="rawResponse" title="原始响应 (code/message/data)" size="small">
        <pre class="text-xs bg-gray-100 p-3 rounded overflow-auto max-h-64">{{ rawResponse }}</pre>
      </a-card>
      
      <a-alert v-if="error" type="error" :message="error" show-icon class="mt-4" />
      
      <div class="mt-6 text-xs text-gray-500">
        说明：此页仅验证前端代理转发 + Axios code=0 解析。网关未启动时会显示网络/超时错误（正常）。
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { probeLeaderboard } from '@/api/client'

const loading = ref(false)
const result = ref<{ code: number; message: string } | null>(null)
const rawResponse = ref<string>('')
const error = ref<string>('')

async function runProbe() {
  loading.value = true
  error.value = ''
  result.value = null
  rawResponse.value = ''
  
  try {
    const body = await probeLeaderboard()  // interceptor returns {code, message, data} on code=0
    result.value = { code: body.code, message: body.message }
    rawResponse.value = JSON.stringify(body, null, 2)
  } catch (e: any) {
    error.value = e.message || '探活失败'
    if (e.code !== undefined) {
      result.value = { code: e.code, message: e.message }
      rawResponse.value = JSON.stringify({ code: e.code, message: e.message, data: e.data }, null, 2)
    } else {
      rawResponse.value = JSON.stringify({ error: e.message }, null, 2)
    }
  } finally {
    loading.value = false
  }
}
</script>
