<template>
  <div class="max-w-md mx-auto mt-10">
    <a-card title="注册" style="width: 100%;">
      <a-form :model="formState" :rules="rules" @finish="onFinish" layout="vertical">
        <a-form-item label="手机号" name="phone">
          <a-input v-model:value="formState.phone" placeholder="11位手机号" />
        </a-form-item>
        <a-form-item label="密码" name="password">
          <a-input-password v-model:value="formState.password" placeholder="6-64位密码" />
        </a-form-item>
        <a-form-item label="昵称（可选）" name="nickname">
          <a-input v-model:value="formState.nickname" placeholder="最多30字" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block>注册</a-button>
        </a-form-item>
      </a-form>
      <div class="text-center mt-4">
        <a @click="goLogin">已有账号？去登录</a>
      </div>
      <a-alert v-if="error" :message="error" type="error" show-icon class="mt-4" />
      <a-alert v-if="success" :message="success" type="success" show-icon class="mt-4" />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router/auto'
import api from '@/api/client'

const router = useRouter()

const formState = reactive({
  phone: '',
  password: '',
  nickname: '',
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
  nickname: [
    { max: 30, message: '昵称长度不能超过 30 字' },
  ],
}

const loading = ref(false)
const error = ref('')
const success = ref('')

async function onFinish() {
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    await api.post('/api/auth/register', {
      phone: formState.phone,
      password: formState.password,
      nickname: formState.nickname || undefined,
    })
    success.value = '注册成功！请使用手机号密码登录。'
    // 注册成功不发 token，按规范需再登录
    setTimeout(() => {
      router.push('/login')
    }, 1200)
  } catch (e: any) {
    if (e.code === 2001) {
      error.value = '手机号已注册，请直接登录或使用其他手机号'
    } else {
      error.value = e.message || '注册失败'
    }
  } finally {
    loading.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>
