import { test, expect } from '@playwright/test'
import { assertNoUnhandled, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ⑧ 顶栏导航：逐项点击导航按钮 → 路由与页面内容断言（含守卫跳转与退出清会话）。
// 注：「榜单」按钮未点击——/leaderboard/api/* 不在两前缀 route-mock 覆盖内，点击将产生
// 真实网络请求；该交互登记未覆盖（见 handoff §10）。
test.describe('顶栏导航（route-mock）', () => {
  test('导航到各业务页并断言内容，退出清会话', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const header = page.locator('header')
    const navBtn = (name: RegExp) => header.getByRole('button', { name })

    // 提交记录
    await navBtn(/提交记录/).click()
    await expect(page).toHaveURL(/\/record-submit/)
    await expect(page.getByText('points 数量: 10')).toBeVisible()

    // 判定/申诉/点赞
    await navBtn(/判定\/申诉\/点赞/).click()
    await expect(page).toHaveURL(/\/verdict/)
    await expect(page.getByText('判定结果轮询 & 申诉 & 点赞')).toBeVisible()

    // 好友（列表按「刷新列表」按钮触发加载）
    await navBtn(/好\s*友/).click()
    await expect(page).toHaveURL(/\/friends/)
    await page.getByRole('button', { name: /刷新列表/ }).click()
    await expect(page.getByText('跑友A', { exact: true })).toBeVisible()

    // 动态
    await navBtn(/动\s*态/).click()
    await expect(page).toHaveURL(/\/feed/)
    await expect(page.getByText('跑友0', { exact: true })).toBeVisible()

    // 通知（铃铛角标：未读数 3 → 通知列表渲染）
    await expect(header.locator('.ant-badge')).toContainText('3')
    await page.locator('header .ant-badge').click()
    await expect(page).toHaveURL(/\/notifications/)
    await expect(page.getByText('通知中心')).toBeVisible()
    await expect(page.getByText('未读', { exact: true })).toBeVisible()

    // 探活（挂载不发请求，按钮在）
    await navBtn(/探\s*活/).click()
    await expect(page).toHaveURL(/\/probe/)
    await expect(page.getByRole('button', { name: /执行探活/ })).toBeVisible()

    // 注册
    await navBtn(/注\s*册/).click()
    await expect(page).toHaveURL(/\/register/)
    await expect(page.getByPlaceholder('11位手机号')).toBeVisible()

    // 登录（已登录 → 守卫跳回首页）
    await navBtn(/登\s*录/).click()
    await expect(page).toHaveURL(/\/$/)

    // 退出 → 清会话（token 键清空）→ 登录页；客户端路由再访受保护页仍被守卫拦回
    // 注：seedSession 的 addInitScript 会在每次整页加载时重新播种 token，故此处不复用 page.goto('/') 断言守卫。
    await navBtn(/退\s*出/).click()
    await expect(page).toHaveURL(/\/login/)
    expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
    await navBtn(/好\s*友/).click()
    await expect(page).toHaveURL(/\/login/)
    assertNoUnhandled(ctx)
  })
})
