<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="规则版本管理 (ADMIN)" class="mb-4">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="403 无权限：非 ADMIN 角色禁止访问管理功能（网关返回 403/1002）。请使用 ADMIN 账号登录后重试。" show-icon />

      <div v-if="role === 'ADMIN'">
        <p class="text-gray-600 mb-4">调用 /verify/rules/versions (POST 创建)、 /gray (PATCH 调灰度 0=回滚)、 /activate (POST 全量)。错误码 4002/4003/4004 直接展示 message。不伪造数据。</p>

        <!-- Create -->
        <a-card title="创建规则版本" size="small" class="mb-4">
          <a-form layout="vertical" @finish="onCreate">
            <a-form-item label="版本号 (可选，留空后端自动生成)">
              <a-input v-model:value="createForm.version" placeholder="v20260917 或留空" />
            </a-form-item>
            <a-form-item label="初始灰度比例 (0-100，默认 0)">
              <a-input-number v-model:value="createForm.grayRatio" :min="0" :max="100" style="width: 120px" />
            </a-form-item>
            <a-form-item label="rules (可选 JSON，留空则后端快照当前基线；新规则试验时填写)">
              <a-textarea v-model:value="createForm.rulesJson" :rows="4" placeholder='{"rules":{"vDrift":20,"bySportType":{...}}}' />
            </a-form-item>
            <a-button type="primary" html-type="submit" :loading="createLoading">创建 (POST /verify/rules/versions)</a-button>
          </a-form>
          <a-alert v-if="createResult" type="success" :message="`创建成功 id=${createResult.id} version=${createResult.version} gray=${createResult.grayRatio} status=${createResult.status}`" class="mt-2" />
          <a-alert v-if="createError" type="error" :message="createError" class="mt-2" />
        </a-card>

        <!-- Gray -->
        <a-card title="调整灰度 (PATCH)" size="small" class="mb-4">
          <div class="flex gap-2 mb-2">
            <a-input v-model:value="grayForm.id" placeholder="version id (如 1)" style="width:120px" />
            <a-input-number v-model:value="grayForm.grayRatio" :min="0" :max="100" placeholder="grayRatio" style="width:120px" />
            <a-button type="primary" @click="onGray" :loading="grayLoading">设置灰度</a-button>
          </div>
          <div class="text-xs text-gray-500">0 = 秒级回滚；100 = 全采样</div>
          <a-alert v-if="grayResult" type="success" :message="`灰度更新 id=${grayResult.id} gray=${grayResult.grayRatio}`" class="mt-2" />
          <a-alert v-if="grayError" type="error" :message="grayError" class="mt-2" />
        </a-card>

        <!-- Activate -->
        <a-card title="全量激活 (POST)" size="small">
          <div class="flex gap-2 mb-2">
            <a-input v-model:value="activateForm.id" placeholder="version id" style="width:120px" />
            <a-button type="primary" @click="onActivate" :loading="activateLoading">全量发布</a-button>
          </div>
          <div class="text-xs text-gray-500">gray=100 + ACTIVE，旧版本 RETIRED</div>
          <a-alert v-if="activateResult" type="success" :message="`激活成功 id=${activateResult.id} status=${activateResult.status} gray=${activateResult.grayRatio}`" class="mt-2" />
          <a-alert v-if="activateError" type="error" :message="activateError" class="mt-2" />
        </a-card>

        <a-alert type="warning" class="mt-4 text-xs">
          提示：创建后从返回或手动记下 id 用于后续 gray/activate。无列表 API，按 appealId 类似手工输入。普通用户直开此 URL 看到 403。
        </a-alert>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getRole } from '@/utils/token'
import { createRuleVersion, updateRuleGray, activateRuleVersion } from '@/api/client'

const role = computed(() => getRole() || '')

const createForm = ref({ version: '', grayRatio: 0, rulesJson: '' })
const createLoading = ref(false)
const createResult = ref<any>(null)
const createError = ref('')

const grayForm = ref({ id: '', grayRatio: 0 })
const grayLoading = ref(false)
const grayResult = ref<any>(null)
const grayError = ref('')

const activateForm = ref({ id: '' })
const activateLoading = ref(false)
const activateResult = ref<any>(null)
const activateError = ref('')

async function onCreate() {
  createLoading.value = true
  createError.value = ''
  createResult.value = null
  try {
    const req: any = {}
    if (createForm.value.version) req.version = createForm.value.version
    if (createForm.value.grayRatio != null) req.grayRatio = createForm.value.grayRatio
    if (createForm.value.rulesJson && createForm.value.rulesJson.trim()) {
      try {
        req.rules = JSON.parse(createForm.value.rulesJson.trim())
      } catch (e) {
        createError.value = 'rules JSON 解析失败: ' + (e as Error).message
        return
      }
    }
    const res = await createRuleVersion(req)
    createResult.value = res.data
  } catch (e: any) {
    const code = e.code ? `[${e.code}] ` : ''
    createError.value = code + (e.message || '创建失败')
  } finally {
    createLoading.value = false
  }
}

async function onGray() {
  if (!grayForm.value.id) return
  grayLoading.value = true
  grayError.value = ''
  grayResult.value = null
  try {
    const res = await updateRuleGray(grayForm.value.id, grayForm.value.grayRatio)
    grayResult.value = res.data
  } catch (e: any) {
    const code = e.code ? `[${e.code}] ` : ''
    grayError.value = code + (e.message || '调灰度失败')
  } finally {
    grayLoading.value = false
  }
}

async function onActivate() {
  if (!activateForm.value.id) return
  activateLoading.value = true
  activateError.value = ''
  activateResult.value = null
  try {
    const res = await activateRuleVersion(activateForm.value.id)
    activateResult.value = res.data
  } catch (e: any) {
    const code = e.code ? `[${e.code}] ` : ''
    activateError.value = code + (e.message || '激活失败')
  } finally {
    activateLoading.value = false
  }
}
</script>
