<template>
  <a-layout style="min-height: 100vh;">
    <a-layout-header style="background: #001529; padding: 0 24px; color: white; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px;">
      <div style="font-size: 18px; font-weight: 600; white-space: nowrap;">运动记录校验控制台</div>
      <div style="display: flex; align-items: center; gap: 4px; flex-wrap: wrap;">
        <a-button type="link" style="color: #fff;" @click="goTo('/record-submit')">提交记录</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/verdict')">判定/申诉/点赞</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/friends')">好友</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/feed')">动态</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/leaderboard')">榜单</a-button>
        <a-button type="link" style="color: #fff;" @click="goTo('/clubs')">跑团</a-button>
        <a-button v-if="isAdmin" type="link" style="color: #0ff; font-weight: bold;" @click="goTo('/admin-rules')">规则版本</a-button>
        <a-button v-if="isAdmin" type="link" style="color: #0ff; font-weight: bold;" @click="goTo('/admin-appeal')">申诉终判</a-button>
        <a-badge :count="unread" :overflow-count="99">
          <a-button type="link" style="color: #fff;" @click="goTo('/notifications')">
            <BellOutlined />
          </a-button>
        </a-badge>
        <a-button type="link" style="color: #fff;" @click="goTo('/probe')">探活</a-button>
        <!-- 登录态感知（TASK-237）：已登录 → 身份标签 + 设置 + 退出；未登录 → 注册/登录 -->
        <template v-if="loggedIn">
          <a-tag color="green" style="margin-inline-end: 8px;">已登录: {{ roleLabel }}</a-tag>
          <a-button type="link" style="color: #fff;" @click="goTo('/settings')">设置</a-button>
          <a-button type="link" style="color: #fff; margin-left: 4px;" @click="logout">退出</a-button>
        </template>
        <template v-else>
          <a-button type="link" style="color: #fff;" @click="goTo('/register')">注册</a-button>
          <a-button type="link" style="color: #fff;" @click="goTo('/login')">登录</a-button>
        </template>
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
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router/auto'
import { BellOutlined } from '@ant-design/icons-vue'
import { clearTokens, getRole, hasAccessToken } from '@/utils/token'
import { useNotificationBell } from '@/composables/useNotificationBell'

const router = useRouter()
const route = useRoute()

const isAdmin = computed(() => getRole() === 'ADMIN')

// 登录态感知（TASK-237）：localStorage 非响应式源，ref 承载并在退出/路由变更时同步，
// 避免 computed 直读缓存陈旧值导致退出后顶栏无法即时恢复未登录态（注册/登录入口）。
const loggedIn = ref(hasAccessToken())
const role = ref(getRole())

/** 身份标签文案：无 role 兜底 USER */
const roleLabel = computed(() => role.value || 'USER')

// 路由变更兜底重读：登录页 setTokens 后跳转 / 或退出跳转 /login 均能同步顶栏态
watch(
  () => route.fullPath,
  () => {
    loggedIn.value = hasAccessToken()
    role.value = getRole()
  },
)

// 未读数共享 composable：App.vue 常驻，挂载启动轮询、卸载清 interval（随会话存续）
const { unread } = useNotificationBell()

function goTo(path: string) {
  router.push(path)
}

function logout() {
  clearTokens()
  loggedIn.value = false
  role.value = null
  router.push('/login')
}
</script>
