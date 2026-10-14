## Proposal: e2e-browser-batch (TASK-227, H1)

### Background
9 课题（213/218-225）连续登记「真实浏览器交互未跑」。TASK-215 曾裁定不引入 Playwright——当时前端债小；今交互面扩大至 feed/删除/筛选/目标，重议引入。

### Plan (MEDIUM: Playwright devDependency + route-mock E2E，web-only)
- 引入 Playwright（chromium 单浏览器）为 web devDependency；**S0 明列例外**：web/package.json + pnpm-lock.yaml 允许触碰（沿 typed-router 先例的 S0 例外格式）；ci.yml 红线零触碰——套件本地执行，不入 CI。
- E2E 采用 **route-mock 模式**：Playwright page.route 拦截 /record/api/* 与 /user/api/* 返回固定夹具，验证前端交互本体（渲染/导航/表单校验/确认弹窗/分页/过滤联动/进度条），零后端依赖零全栈起服务。
- Smoke 清单（预注册 8 条，recon 可调±）：① 登录跳转 ② 首页记录列表渲染 ③ 提交表单校验与提交 ④ 我的记录筛选联动+重置页码 ⑤ 删除二次确认与列表刷新 ⑥ feed 卡片与分页 ⑦ 目标卡片进度与对话框 ⑧ 顶栏导航。
- 证据：E2E 运行输出与 trace 落 .trae/tmp（gitignored）；handoff 登记清单逐条红绿。

### Non-Goals
真实后端全栈联调（--it 层，留 H2/后续）、CI 集成、多浏览器/移动端、视觉回归、性能测试、后端任何改动。
