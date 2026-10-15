import { test, expect } from '@playwright/test'
import { assertNoUnhandled, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// ⑥ feed 卡片与分页：卡片渲染作者/类型/距离/状态；翻页加载第 2 页内容。
test.describe('feed 卡片与分页（route-mock）', () => {
  test('第 1 页卡片渲染，翻页显示第 2 页条目', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/feed')

    await expect(page.locator('.ant-card-head-title', { hasText: '好友动态' }).first()).toBeVisible()
    await expect(page.getByText('跑友0', { exact: true })).toBeVisible()
    await expect(page.getByText('跑友1', { exact: true })).toBeVisible()
    await expect(page.getByText('RUNNING', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('km', { exact: false }).first()).toBeVisible()

    const feedCalls = () => ctx.apiRequests.filter((r) => r.includes('GET /record/api/records/feed'))
    expect(feedCalls()).toHaveLength(1)

    // 点赞交互（TASK-236）：卡片点赞按钮可见且展示计数（stateful mock 初态 0 赞/未赞）；
    // 点击 → POST 已赞高亮（计数 +1）；再点 → DELETE 取消恢复初态
    const firstCard = page.locator('.space-y-3 .ant-card').filter({ hasText: '跑友0' })
    await expect(firstCard.getByRole('button', { name: /^赞 \(0\)$/ })).toBeVisible()
    await firstCard.getByRole('button', { name: /^赞 \(0\)$/ }).click()
    // 先等按钮进入已赞态（POST 响应落库后 UI 更新），再断言请求确已发送
    await expect(firstCard.getByRole('button', { name: /^已赞 \(1\)$/ })).toBeVisible()
    expect(ctx.apiRequests.filter((r) => r.includes('POST /record/api/records/2000/like'))).toHaveLength(1)
    await firstCard.getByRole('button', { name: /^已赞 \(1\)$/ }).click()
    await expect(firstCard.getByRole('button', { name: /^赞 \(0\)$/ })).toBeVisible()
    expect(ctx.apiRequests.filter((r) => r.includes('DELETE /record/api/records/2000/like'))).toHaveLength(1)

    // 22 条 → 第 2 页（2 条）：翻页后请求 page=2、内容切换
    await page.locator('.ant-pagination-item-2').click()
    await expect(page.getByText('跑友20', { exact: true })).toBeVisible()
    await expect(page.getByText('跑友21', { exact: true })).toBeVisible()
    await expect(page.getByText('跑友0', { exact: true })).toBeHidden()
    expect(feedCalls().length).toBeGreaterThanOrEqual(2)
    assertNoUnhandled(ctx)
  })
})
