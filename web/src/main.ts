import { createApp } from 'vue'
import App from './App.vue'
import { createRouter, createWebHistory } from 'vue-router/auto'
import { VueQueryPlugin } from '@tanstack/vue-query'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './assets/main.css'
import { hasAccessToken, setAuthFailHandler, clearTokens } from '@/utils/token'

const router = createRouter({
  history: createWebHistory(),
})

// 路由守卫：无 access 不能进控制台；已登录访问登录页去控制台
router.beforeEach((to, from, next) => {
  const loggedIn = hasAccessToken()
  if (to.path === '/login' && loggedIn) {
    next('/')
    return
  }
  // 控制台布局（/ 及非公开页）需登录；probe/register/login 公开
  const publicPaths = ['/login', '/register', '/probe']
  const requiresAuth = !publicPaths.some(p => to.path === p || to.path.startsWith(p + '/'))
  if (requiresAuth && !loggedIn) {
    next('/login')
    return
  }
  next()
})

const app = createApp(App)
app.use(Antd)
app.use(router)
app.use(VueQueryPlugin)
app.mount('#app')

// 注册 401 refresh 失败处理器：清存储并跳登录（由 client 触发，防止死循环）
setAuthFailHandler(() => {
  // 使用 replace 避免历史堆栈问题
  router.replace('/login')
})
