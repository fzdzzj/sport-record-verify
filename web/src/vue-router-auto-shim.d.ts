// 版本兼容垫片（本文件手写，不是生成物；生成物是同目录的 typed-router.d.ts）
//
// 背景订正（TASK-227 停止上报）：unplugin-vue-router@0.19.2 **不再提供** `vue-router/auto` 的
// createRouter 自动注入（其 package.json#exports 无 ./auto；dist 内只有 MODULE_ROUTES_PATH =
// "vue-router/auto-routes"），故 `vue-router/auto` 落到 vue-router 侧空占位、createRouter 不再带上
// 路由表——运行时 createRouterMatcher(undefined) 抛错，应用无法挂载。正确契约是显式取 routes：
//   import { createRouter } from 'vue-router'
//   import { routes } from 'vue-router/auto-routes'
//   createRouter({ history: createWebHistory(), routes })
// 本垫片仍提供 `vue-router/auto` 的**类型入口**（App.vue 与各页面从该子路径取 useRouter），
// 但删除了会掩盖上述缺陷的 createRouter 声明；另补 auto-routes 的**值导出**类型
// （生成的 typed-router.d.ts 仅声明 RouteNamedMap 等类型，无 routes 值导出）。
declare module 'vue-router/auto' {
  export * from 'vue-router'
}

declare module 'vue-router/auto-routes' {
  import type { RouteRecordRaw } from 'vue-router'

  export const routes: RouteRecordRaw[]
}
