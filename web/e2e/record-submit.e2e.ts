import { test, expect } from '@playwright/test'
import { assertNoUnhandled, assertNoUnmocked, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ③ 提交表单校验与提交：清空轨迹 → 提交按钮禁用（校验门）；加载样例 → 可提交；
// 提交 → route-mock POST /record/api/records → 结果卡片渲染（recordId/status/duplicated）。
// 注：/record-submit 走顶栏客户端导航进入——vite dev/preview 均按 server.proxy 把 /record* 前缀
// 转发到网关（无后端时整页直载 500），真实用户路径是点击顶栏「提交记录」。
async function openSubmitPage(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.locator('header').getByRole('button', { name: /提交记录/ }).click()
  await expect(page).toHaveURL(/\/record-submit/)
}

test.describe('提交表单校验与提交（route-mock）', () => {
  test('清空后提交按钮禁用（校验门），重新加载样例后恢复', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await openSubmitPage(page)

    // 样例自动加载：10 点，提交按钮可用（顶栏亦有同名导航按钮，故限定 main 内容区）
    await expect(page.getByText('points 数量: 10')).toBeVisible()
    const submitBtn = page.getByRole('main').getByRole('button', { name: /提交记录/ })
    await expect(submitBtn).toBeEnabled()

    // 清空 → 0 点 → 禁用（无请求发出）
    await page.getByRole('button', { name: /清\s*空/ }).click()
    await expect(page.getByText('points 数量: 0')).toBeVisible()
    await expect(submitBtn).toBeDisabled()
    expect(ctx.apiRequests.filter((r) => r.startsWith('POST /record/api/records'))).toEqual([])

    // 重新加载样例 → 恢复可用
    await page.getByRole('button', { name: /加载内置样例/ }).click()
    await expect(page.getByText('points 数量: 10')).toBeVisible()
    await expect(submitBtn).toBeEnabled()
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })

  test('选择骑行类型提交 → 结果卡片渲染（recordId/status/duplicated）', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await openSubmitPage(page)

    // 运动类型 → 2 - CYCLING
    await page.locator('.ant-select').first().click()
    await page.getByText('2 - CYCLING', { exact: true }).click()

    await page.getByRole('main').getByRole('button', { name: /提交记录/ }).click()

    // 结果反馈：a-alert 仅渲染 message 槽（默认槽在本版 antdv 不透出，status/duplicated 文本不落屏，
    // 见 handoff 未覆盖/发现项）；recordId 由「本会话记录」列表承载。
    // TASK-233：Alert 详情插槽与历史会话列表均含 recordId: 9001 → 须限定在 .ant-alert 容器内消 strict mode 冲突。
    await expect(page.locator('.ant-alert-message', { hasText: '提交结果' })).toBeVisible()
    await expect(page.locator('.ant-alert').getByText('recordId: 9001')).toBeVisible()

    // POST 载荷断言：sportType=2（骑行的选择真实随请求下发）
    expect(ctx.submitBodies).toHaveLength(1)
    expect(ctx.submitBodies[0].sportType).toBe(2)
    expect(typeof ctx.submitBodies[0].requestId).toBe('string')
    expect((ctx.submitBodies[0].points as unknown[]).length).toBeGreaterThanOrEqual(1)
    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })
})
