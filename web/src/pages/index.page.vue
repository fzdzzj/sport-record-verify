<template>
  <div class="max-w-2xl mx-auto">
    <a-card title="欢迎 - 运动记录校验业务控制台">
      <p>已实现主演示链路（仅 web 侧）：</p>
      <ul class="list-disc pl-5 mt-2">
        <li>提交记录（内置样例轨迹 + POST /record/api/records）</li>
        <li>判定轮询 + 申诉（verdict=0 轮询中；1 可赞；2 可申诉）</li>
        <li>通过记录点赞（6001 错误展示）</li>
        <li>好友申请/处理/列表</li>
        <li>总榜与好友榜</li>
      </ul>
      <p class="mt-4">使用顶部导航进入各页。需先 <a @click="goLogin">登录</a>（依赖 auth 会话）。</p>
      <p class="mt-2 text-sm text-gray-600">本会话 recordId 存 sessionStorage；刷新或换浏览器不会从服务端恢复（诚实无列表 API）。</p>
      <div class="mt-4">
        <a-button type="primary" @click="go('/record-submit')">开始：提交样例轨迹</a-button>
        <a-button class="ml-2" @click="go('/leaderboard')">查看榜单</a-button>
      </div>
    </a-card>
    <a-alert type="info" class="mt-4" message="演示要求：网关+服务 app.auth.enabled=true ；前端始终带 Bearer；不伪造 userId。" />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router/auto'
const router = useRouter()
function go(p: string) { router.push(p) }
function goLogin() { router.push('/login') }
</script>
