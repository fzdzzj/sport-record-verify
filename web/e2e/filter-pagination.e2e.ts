import { test, expect } from '@playwright/test'
import { assertNoUnhandled, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ④ 筛选联动 + 重置页码：翻页后变更筛选 → 请求回落 page=1 且列表按筛选缩减；重置恢复全量。
test.describe('筛选联动与重置页码（route-mock）', () => {
  test('翻页 → 筛选跑步 → page 重置为 1 且列表缩减 → 重置恢复 20 行', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const rows = page.locator('.ant-table-tbody tr')
    await expect(rows).toHaveCount(20)

    // 翻到第 2 页：请求 page=2，首行 id 1020
    await page.locator('.ant-pagination-item-2').click()
    await expect(rows).toHaveCount(5)
    await expect(rows.first()).toContainText('1020')
    expect(stores.records.lastQuery?.get('page')).toBe('2')

    // 运动类型 → 跑步（sportType=1）：页码重置为 1，列表缩减为 9 行（i%3==0 共 9 条）
    const sportSelect = page.locator('.ant-select', { hasText: '运动类型' }).first()
    await sportSelect.click()
    await page.getByText('跑步', { exact: true }).last().click()
    await expect(rows).toHaveCount(9)
    expect(stores.records.lastQuery?.get('sportType')).toBe('1')
    expect(stores.records.lastQuery?.get('page')).toBe('1')

    // 重置：筛选清空、页码回到 1、全量 20 行
    await page.getByRole('button', { name: /重\s*置/ }).click()
    await expect(rows).toHaveCount(20)
    expect(stores.records.lastQuery?.get('sportType')).toBeNull()
    expect(stores.records.lastQuery?.get('page')).toBe('1')
    assertNoUnhandled(ctx)
  })
})
