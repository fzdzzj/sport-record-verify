import { createApp } from 'vue'
import App from './App.vue'
import { createRouter, createWebHistory } from 'vue-router/auto'
import { VueQueryPlugin } from '@tanstack/vue-query'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './assets/main.css'

const router = createRouter({
  history: createWebHistory(),
})

const app = createApp(App)
app.use(Antd)
app.use(router)
app.use(VueQueryPlugin)
app.mount('#app')
