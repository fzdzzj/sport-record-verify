<template>
  <a-layout style="min-height: 100vh;">
    <a-layout-header style="background: #001529; padding: 0 24px; color: white; display: flex; align-items: center; justify-content: space-between;">
      <div style="font-size: 18px; font-weight: 600;">运动记录校验控制台 (Scaffold)</div>
      <div>
        <a-button type="link" style="color: #fff;" @click="goTo('/register')">注册</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/login')">登录</a-button>
        <a-button type="link" style="color: #fff; margin-left: 8px;" @click="goTo('/probe')">探活页</a-button>
      </div>
    </a-layout-header>
    <a-layout-content style="padding: 24px; background: #f0f2f5;">
      <router-view />
    </a-layout-content>
    <a-layout-footer style="text-align: center; background: #f0f2f5;">
      Web Console Scaffold • 仅骨架，无业务逻辑
    </a-layout-footer>
  </a-layout>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router/auto'

const router = useRouter()

function goTo(path: string) {
  router.push(path)
}
</script>
