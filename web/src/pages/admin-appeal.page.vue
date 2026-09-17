<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="申诉终判 (ADMIN)" class="mb-4">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="403 无权限：非 ADMIN 角色禁止访问管理功能（网关返回 403/1002）。请使用 ADMIN 账号登录后重试。" show-icon />

      <div v-if="role === 'ADMIN'">
        <p class="text-gray-600 mb-4">终判 POST /admin/api/appeals/{id}/review 。手工输入 appealId（从业务控制台申诉后记下）。pass=true 改判通过，false 维持拒绝。不新增列表 API。</p>

        <div>
          <a-form-item label="appealId (手工输入，必填)" required>
            <a-input v-model:value="form.id" placeholder="例如 123" style="width: 200px" />
          </a-form-item>
          <a-form-item label="operator (复核操作人)" required>
            <a-input v-model:value="form.operator" placeholder="admin" />
          </a-form-item>
          <a-form-item label="recheckResult (复核结论，可选)">
            <a-textarea v-model:value="form.recheckResult" :rows="3" placeholder="详细结论..." />
          </a-form-item>
          <div class="flex gap-2 mt-2">
            <a-button type="primary" :loading="loading" @click="onReview(true)">终判通过 (pass=true)</a-button>
            <a-button danger :loading="loading" @click="onReview(false)">维持拒绝 (pass=false)</a-button>
          </div>
        </div>

        <a-alert v-if="result" type="success" class="mt-4">
          终判成功: id={{ result.id }} recordId={{ result.recordId }} status={{ result.status }} operator={{ result.operator }}
          <div v-if="result.recheckResult">结论: {{ result.recheckResult }}</div>
        </a-alert>
        <a-alert v-if="error" type="error" :message="error" class="mt-4" />

        <a-alert type="info" class="mt-4 text-xs">
          提示：从 verdict 页提交申诉后，记下返回的 appeal id（或从 DB/日志）。普通用户打开此页必须看到 403，不显示表单。
        </a-alert>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getRole } from '@/utils/token'
import { reviewAppeal } from '@/api/client'

const role = computed(() => getRole() || '')

const form = ref({ id: '', operator: 'admin', recheckResult: '' })
const loading = ref(false)
const result = ref<any>(null)
const error = ref('')

async function onReview(pass: boolean) {
  if (!form.value.id || !form.value.operator) {
    error.value = 'appealId 和 operator 必填'
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const dto = {
      operator: form.value.operator,
      pass: !!pass,
      recheckResult: form.value.recheckResult.trim() || undefined
    }
    const res = await reviewAppeal(form.value.id, dto)
    result.value = res.data
  } catch (e: any) {
    const code = e.code ? `[${e.code}] ` : ''
    error.value = code + (e.message || '终判失败')
  } finally {
    loading.value = false
  }
}
</script>
