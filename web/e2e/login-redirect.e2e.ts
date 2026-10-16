import { test, expect } from '@playwright/test'
import { assertNoUnhandled, assertNoUnmocked, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ① 登录跳转：守卫（未登录拦回 /login；已登录访问 /login 自动跳转首页）。零后端依赖。
test.describe('登录跳转（route-mock）', () => {
  test('未登录访问控制台 → 守卫跳转登录页', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByPlaceholder('11位手机号')).toBeVisible()
    await expect(page.getByPlaceholder('密码')).toBeVisible()
    expect(ctx.apiRequests).toEqual([])
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })

  test('已登录访问 /login → 自动跳转首页并渲染记录列表', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/login')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByText('我的记录 / 个人统计')).toBeVisible()
    await expect(page.getByText('当前 5 天 · 最长 12 天')).toBeVisible()
    expect(ctx.apiRequests.filter((r) => r === 'GET /record/api/records/my')).toHaveLength(1)
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })
})
