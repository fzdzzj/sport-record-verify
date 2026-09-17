<template>
  <div class="max-w-2xl mx-auto">
    <a-card title="欢迎 - Web 控制台脚手架">
      <p>前端工程已初始化（Vue 3 + Vite + TS + Antd Vue 4 + Tailwind 4 + Axios + Vue Query + 文件路由）。</p>
      <p class="mt-2">使用 Vite 代理原样转发网关前缀到 127.0.0.1:8080（无 rewrite）。</p>
      <p class="mt-2">Axios 统一按 <code>code === 0</code> 判断成功，否则抛出 <code>message</code>。</p>
      <p class="mt-4 text-gray-500">点击顶部导航尝试登录占位或探活页。</p>
      <p class="mt-2 text-xs">注意：业务页、登录实现、CORS 等在独立变更中交付。</p>
    </a-card>
  </div>
</template>

<script setup lang="ts">
</script>
