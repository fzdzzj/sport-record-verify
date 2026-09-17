<template>
  <div class="max-w-2xl mx-auto">
    <a-card title="ADMIN 治理面控制台">
      <a-alert v-if="role !== 'ADMIN'" type="error" message="403 无权限访问" />
      <div v-else>
        <p>欢迎 ADMIN。使用顶部菜单「规则版本」或「申诉终判」。</p>
        <p class="text-xs text-gray-500 mt-2">仅 role=ADMIN 显示入口；直开 URL 仍展示 403。</p>
        <a-button @click="go('/admin-rules')" class="mt-2">规则版本管理</a-button>
        <a-button @click="go('/admin-appeal')" class="ml-2">申诉终判</a-button>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router/auto'
import { getRole } from '@/utils/token'

const router = useRouter()
const role = computed(() => getRole() || '')

function go(p: string) { router.push(p) }
</script>
