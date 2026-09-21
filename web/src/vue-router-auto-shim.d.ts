// 版本兼容垫片（本文件手写，不是生成物；生成物是同目录的 typed-router.d.ts）
//
// 为什么需要它：`main.ts` 等处按 unplugin-vue-router 的约定从 `vue-router/auto` 取 createRouter，
// 该子路径的运行时由插件的 Vite 别名提供（自动带上文件路由表，故调用时可省略 routes），
// 但它的**类型入口**由 vue-router 侧的空占位文件负责：本机实测 vue-router@4.6.4 的
// node_modules/vue-router/vue-router-auto.d.ts 全文只有 36 字节一行注释
// 「// augmented by unplugin-vue-router」，而本仓使用的 unplugin-vue-router@0.19.2 不改写该文件。
// 结果：一旦移除本垫片，`pnpm type-check` 报 11 条——8 条 TS2306（'vue-router/auto' is not a module，
// 覆盖 App.vue、main.ts 与 5 个 page）+ 3 条 TS7006（createRouter 退化为无类型后守卫参数变隐式 any）。
//
// 这里只做类型声明，不改任何运行时行为：`export *` 交还 vue-router 的全部导出，
// 再按插件运行时契约单独声明 createRouter 接受省略 routes 的选项。
declare module 'vue-router/auto' {
  import type { Router, RouterOptions } from 'vue-router'

  export * from 'vue-router'

  export function createRouter(options: Omit<RouterOptions, 'routes'>): Router
}
