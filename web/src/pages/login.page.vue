<template>
  <div class="max-w-md mx-auto mt-10">
    <a-card title="登录" style="width: 100%;">
      <a-form :model="formState" :rules="rules" @finish="onFinish" layout="vertical">
        <a-form-item label="手机号" name="phone">
          <a-input v-model:value="formState.phone" placeholder="11位手机号" />
        </a-form-item>
        <a-form-item label="密码" name="password">
          <a-input-password v-model:value="formState.password" placeholder="密码" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block>登录</a-button>
        </a-form-item>
      </a-form>
      <div class="text-center mt-4">
        <a @click="goRegister">没有账号？去注册</a>
      </div>
      <a-alert v-if="error" :message="error" type="error" show-icon class="mt-4" />
    </a-card>
    <div class="text-xs text-gray-500 mt-4 text-center">
      演示：登录成功后进入控制台；凭据错 401/1001，锁定 403/1002（或 code 1002）
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router/auto'
import api from '@/api/client'
import { setTokens } from '@/utils/token'

const router = useRouter()

const formState = reactive({
  phone: '',
  password: '',
})

const rules = {
  phone: [
    { required: true, message: '手机号不能为空' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' },
  ],
  password: [
    { required: true, message: '密码不能为空' },
    { min: 6, max: 64, message: '密码长度须在 6-64 位' },
  ],
}

const loading = ref(false)
const error = ref('')

async function onFinish() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/api/auth/login', {
      phone: formState.phone,
      password: formState.password,
    })
    // res = {code:0, message, data: {accessToken, refreshToken, role, ...}}
    const t = res.data
    if (t && t.accessToken && t.refreshToken && t.role) {
      setTokens(t.accessToken, t.refreshToken, t.role)
      // 成功保存双 token 与 role
      router.push('/')
    } else {
      error.value = '登录响应异常'
    }
  } catch (e: any) {
    const status = e.status || e.original?.response?.status
    if (status === 401 || e.code === 1001) {
      error.value = '凭据错误：手机号或密码错误'
    } else if (status === 403 || e.code === 1002) {
      error.value = '账号已临时锁定，请稍后重试'
    } else {
      error.value = e.message || '登录失败'
    }
  } finally {
    loading.value = false
  }
}

function goRegister() {
  router.push('/register')
}
</script>
