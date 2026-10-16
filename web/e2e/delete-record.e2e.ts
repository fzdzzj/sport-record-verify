import { test, expect } from '@playwright/test'
import { assertNoUnhandled, assertNoUnmocked, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ⑤ 删除确认与刷新：Popconfirm 取消不发 DELETE；确认后 DELETE 发出、列表刷新（首行 1000 → 1001）。
test.describe('删除确认与列表刷新（route-mock）', () => {
  test('取消不发删除请求；确认后删除并刷新列表', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const rows = page.locator('.ant-table-tbody tr')
    await expect(rows).toHaveCount(20)
    await expect(rows.first()).toContainText('1000')

    const deletes = () => ctx.apiRequests.filter((r) => r.startsWith('DELETE /record/api/records/'))

    // 删除 → Popconfirm → 取消：无请求、记录仍在
    await rows.first().getByText('删除', { exact: true }).click()
    await expect(page.locator('.ant-popover')).toBeVisible()
    await page.getByRole('button', { name: /取\s*消/ }).click()
    await expect(page.locator('.ant-popover')).toBeHidden()
    expect(deletes()).toHaveLength(0)
    await expect(rows.first()).toContainText('1000')

    // 再删除 → 确认：DELETE 发出、列表刷新（id 1000 消失、1001 成为首行）
    await rows.first().getByText('删除', { exact: true }).click()
    await page.getByRole('button', { name: /确\s*认/ }).click()
    await expect.poll(() => deletes().length).toBeGreaterThan(0)
    await expect(rows.first()).toContainText('1001')
    await expect(page.locator('.ant-table-tbody')).not.toContainText('1000')
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })
})
