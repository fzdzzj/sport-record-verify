<template>
  <a-layout style="min-height: 100vh;">
    <a-layout-header style="background: #001529; padding: 0 24px; color: white; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px;">
      <div style="font-size: 18px; font-weight: 600; white-space: nowrap;">运动记录校验控制台</div>
      <div style="display: flex; align-items: center; gap: 4px; flex-wrap: wrap;">
        <a-button type="link" style="color: #fff;" @click="goTo('/record-submit')">提交记录</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/verdict')">判定/申诉/点赞</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/friends')">好友</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/leaderboard')">榜单</a-button>
        <a-button v-if="isAdmin" type="link" style="color: #0ff; font-weight: bold;" @click="goTo('/admin-rules')">规则版本</a-button>
        <a-button v-if="isAdmin" type="link" style="color: #0ff; font-weight: bold;" @click="goTo('/admin-appeal')">申诉终判</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/probe')">探活</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/register')">注册</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/login')">登录</a-button>
        <a-button type="link" style="color: #fff; margin-left: 4px;" @click="logout">退出</a-button>
      </div>
    </a-layout-header>
    <a-layout-content style="padding: 24px; background: #f0f2f5;">
      <router-view />
    </a-layout-content>
    <a-layout-footer style="text-align: center; background: #f0f2f5; font-size: 12px;">
      Web 业务控制台 (add-web-record-console + add-web-admin-console) • ADMIN 菜单仅 role=ADMIN 可见 • 非 ADMIN 打开管理 URL 展示 403
    </a-layout-footer>
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router/auto'
import { clearTokens, getRole } from '@/utils/token'

const router = useRouter()

const isAdmin = computed(() => getRole() === 'ADMIN')

function goTo(path: string) {
  router.push(path)
}

function logout() {
  clearTokens()
  router.push('/login')
}
</script>
