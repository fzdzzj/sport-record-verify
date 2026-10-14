# TASK-228: web 启动级路由缺陷修复（P0 发现，MEDIUM 小）

派发基线：HEAD=69602b0（TASK-227 停止态笔之上），工作树干净。发现链：TASK-227 S3.3 停止上报（e1610de/69602b0）——E2E 批头号价值发现。token 基线 SUM=2036。

## S0 红线

恰 2 文件：web/src/main.ts + web/src/vue-router-auto-shim.d.ts（+台账 4 文件）。零页面/路由/组件/后端变更；typed-router.d.ts 零 diff；ci.yml/vite.config.ts/pnpm-lock 零触碰（本修复零依赖变更）。命令行禁中文。

## S2 预注册

main.ts 显式传 routes；shim 删除 `Omit<RouterOptions,'routes'>` 掩盖性声明、改 auto-routes 值声明；dev 与 preview 双形态挂载验证。

## S2.5 判别式

R1 根因复现（exports 断言）+ 修复后双形态可挂载；R2 web 四件套全绿（test 31/type-check/build/typed-router 零 diff）；R3 恰 2+4 文件全等；R4 token/词面/契约/diff --check/行尾。功能面由同批 TASK-227 E2E 12 用例承载。

## S3 停止条件

修复扩散超 2 文件；或需动依赖/配置才能挂载。

## S6 门禁

web 四件套 + 双形态挂载证据 + 契约双态（--open TASK-228 --baseline=69602b0）+ token + 词面 + diff --check + 行尾（改文件保基线）。独立复核四块（用户派发，与 TASK-227 同批评审）。
