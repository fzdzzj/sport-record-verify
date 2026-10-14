import { test, expect } from '@playwright/test'
import { assertNoUnhandled, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'
import { pageOneDistanceSum } from './fixtures/records'

// ② 记录列表渲染：登录态首页统计卡 + 记录表格（route-mock 返回 25 条 / 第 1 页 20 条）。
test.describe('记录列表渲染（route-mock）', () => {
  test('统计卡与表格渲染：记录数/总里程/通过率/连续打卡/可见性列', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const myCard = page.locator('.ant-card', { hasText: '我的记录 / 个人统计' }).first()
    await expect(myCard.getByText('记录数')).toBeVisible()
    await expect(myCard.getByText('25', { exact: true })).toBeVisible()
    await expect(myCard.getByText(pageOneDistanceSum().toFixed(2), { exact: true })).toBeVisible()
    await expect(myCard.getByText('25.0%', { exact: true })).toBeVisible()
    await expect(myCard.getByText('当前 5 天 · 最长 12 天')).toBeVisible()

    // 表格 20 行；首行 id 1000（可见性 2 → 仅自己 标签）
    const rows = page.locator('.ant-table-tbody tr')
    await expect(rows).toHaveCount(20)
    await expect(rows.first()).toContainText('1000')
    await expect(rows.first()).toContainText('仅自己')
    await expect(rows.first()).toContainText('RUNNING')

    // 25 条 → 分页器可见（共 25 条）
    await expect(page.locator('.ant-pagination')).toBeVisible()
    assertNoUnhandled(ctx)
  })
})
