import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import VueRouter from 'unplugin-vue-router/vite'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    VueRouter({
      routesFolder: './src/pages',
      extensions: ['.page.vue'],
      dts: './src/typed-router.d.ts',
    }),
    tailwindcss(),
    vue(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  test: {
    name: 'web',
    // DOM 环境：token.ts 依赖 localStorage / window（happy-dom 提供）
    environment: 'happy-dom',
    // 显式 import 测试 API（describe/it/expect/vi），不注入全局
    globals: false,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/user': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/record/': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/leaderboard': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/verify': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/admin/': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
      '/mapmatch': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined),
      },
    },
  },
})
