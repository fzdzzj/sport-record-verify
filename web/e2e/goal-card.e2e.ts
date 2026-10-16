import { test, expect } from '@playwright/test'
import { assertNoUnhandled, assertNoUnmocked, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ⑦ 目标卡片与对话框：卡片渲染（周/月标签、进度、已达成徽标）；设置目标弹窗 upsert；
// 编辑弹窗更新目标值；删除 Popconfirm 移除目标。
test.describe('目标卡片与对话框（route-mock）', () => {
  const goalSection = (page: import('@playwright/test').Page) =>
    page.locator('div.mb-4.p-4.rounded', { hasText: '运动目标与进度' }).first()

  test('目标卡片渲染：周/月标签、进度条、已达成徽标', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const section = goalSection(page)
    await expect(section.getByText('周目标')).toBeVisible()
    await expect(section.getByText('月目标')).toBeVisible()
    // TASK-257 条件项基线红订正：TASK-242 B19 距离格式化两位小数（'32.50 / 50.00'，
    // 旧断言 '32.5 / 50' 自 B19 起在基线即红，1772102 worktree 实测复现）
    await expect(section.getByText('距离：32.50 / 50.00 km')).toBeVisible()
    await expect(section.getByText('次数：12 / 12 次')).toBeVisible()
    await expect(section.getByText('已达成')).toBeVisible()
    await expect(section.locator('.ant-progress')).toHaveCount(2)
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })

  test('设置目标弹窗：upsert 新目标并刷新列表', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const section = goalSection(page)
    await section.getByRole('button', { name: /设置目标/ }).click()

    const modal = page.locator('.ant-modal')
    await expect(modal).toBeVisible()
    await expect(modal.getByText('设置运动目标')).toBeVisible()

    // 指标 → 次数（1）；目标值 → 20
    await modal.locator('.ant-select').nth(1).click()
    await page.getByText('次数 (次)', { exact: true }).last().click()
    await modal.locator('.ant-input-number input').fill('20')

    await modal.getByRole('button', { name: /保\s*存/ }).click()
    await expect(page.getByText('运动目标设置成功')).toBeVisible()

    // 新目标（周·次数 20）出现，进度条 2 → 3
    await expect(section.getByText('次数：0 / 20 次')).toBeVisible()
    await expect(section.locator('.ant-progress')).toHaveCount(3)
    expect(ctx.submitBodies).toHaveLength(0) // 无关断言保持：提交载荷未混入
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })

  test('编辑弹窗更新目标值；删除 Popconfirm 移除目标', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/')

    const section = goalSection(page)
    // 编辑第一条（周·距离 50）：弹窗预填 50 → 改 60
    await section.locator('a', { hasText: '编辑' }).first().click()
    const modal = page.locator('.ant-modal')
    await expect(modal).toBeVisible()
    await expect(modal.getByText('编辑运动目标')).toBeVisible()
    await modal.locator('.ant-input-number input').fill('60')
    await modal.getByRole('button', { name: /保\s*存/ }).click()
    await expect(section.getByText('距离：32.50 / 60.00 km')).toBeVisible()

    // 删除第二条（月·次数 12）：Popconfirm 确认 → 卡片消失
    await section.locator('a', { hasText: '删除' }).last().click()
    await expect(page.locator('.ant-popover')).toBeVisible()
    await page.getByRole('button', { name: /确\s*认/ }).click()
    await expect(section.getByText('次数：12 / 12 次')).toBeHidden()
    await expect(section.locator('.ant-progress')).toHaveCount(1)
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })
})
