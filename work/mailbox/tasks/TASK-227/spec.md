# TASK-227: E2E 真实浏览器验证批（H1，P6，MEDIUM）

派发基线：TASK-226 完成后接续（同一执行序）。工作树干净；token 基线 SUM=2036。提案三件套=spec/changes/e2e-browser-batch/。

## S0 红线与例外

- **S0 明列例外（沿 typed-router 先例格式）**：web/package.json 与 web/pnpm-lock.yaml 允许触碰（Playwright devDependency + e2e script）；除此之外 web 配置、vite.config.ts、typed-router.d.ts（无新路由）、一切后端/Java/sql/pom/ci.yml/scripts/compose 零触碰。
- E2E 本地执行，**不入 CI**；证据与 trace 落 gitignored .trae/tmp。
- Playwright 引入重议裁决（预注册）：TASK-215 裁定不引入时前端债小，现 9 课题积账+交互面扩大，重议通过；chromium 单浏览器，版本锁定。

## S1 已知事实

web 为 vue3+vite+arco；API 前缀 /record/api/* 与 /user/api/*（client.ts）；页面：登录、index（记录列表/统计/目标卡片/过滤条）、feed、record-detail、notifications（recon 核对页面全集与路由）。

## S2 预注册设计

- e2e/ 目录：playwright.config.ts（chromium、webServer 起 vite dev 或 preview）、fixtures/（用户/记录/目标/feed 夹具）、8 条 spec（登录跳转/记录列表渲染/提交表单校验与提交/筛选联动+重置页码/删除确认与刷新/feed 卡片与分页/目标卡片与对话框/顶栏导航）。
- route-mock：page.route 拦截两前缀 API 返回夹具；零后端依赖。
- package.json：devDependency playwright + @playwright/test，scripts.e2e；浏览器二进制不入库（.gitignore 确认）。

## S2.5 判别式

R1 依赖落地（pnpm install 成功、版本锁定、lock 同步）；R2 8 条 spec 全绿（每条断言核心交互而非仅页面加载）；R3 route-mock 覆盖两前缀零真实网络请求（断言 no-unhandled）；R4 web 四件套不回归（test 31/type-check/build 3283/typed-router 零 diff）；R5 证据留痕（运行输出+至少 1 条 trace 落 .trae/tmp 并在 handoff 登记）；R6 后端零 diff。

## S3 停止条件

1. Playwright 安装或 chromium 下载被网络阻断且无镜像可用。
2. route-mock 无法承载某交互（需真实后端）→ 该条登记未覆盖并降清单，不引入后端。
3. 需改 vite.config/路由/后端才能跑通 → 停止上报。

## S4 白名单（预估 12-16）

web/package.json、web/pnpm-lock.yaml、web/e2e/**（config+fixtures+8 spec）、web/.gitignore（如需）、README；台账 4 文件。逐字符全路径登记。

## S6 门禁（MEDIUM，web-only）

web 四件套 + E2E 套件全绿 + typed-router 零 diff + diff --check + 行尾（新文件纯 LF 无 BOM）+ token 2036 持平 + 词面门 ZERO_HIT/探针正控 + 契约双态（--open TASK-227 --baseline=<开工 HEAD>）。零 Maven（政策 web-only 档）。独立复核四块（用户派发）；指导亲核后与 TASK-226 同批推送，CI 绿为收口前提。

## S7 回填

判别式红绿 + E2E 逐条清单证据 + 偏差 + 未覆盖（真实后端全栈联调仍留 H2/后续、CI 集成未做、多浏览器未覆盖）。
