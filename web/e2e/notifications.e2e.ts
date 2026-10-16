import { test, expect } from '@playwright/test'
import { assertNoUnhandled, assertNoUnmocked, createApiCtx, createStores, installRouteMocks, seedSession } from './helpers'

// TASK-257 E14：通知页列表渲染 + 三按钮真实点击交互（单条标已读/批量标记已读/清空已读），
// 状态化 notification store 驱动 UI 与未读徽标流转（3 → 2 → 0），清空已读收缩列表至空态。
test.describe('通知页写路径与徽标收敛（route-mock）', () => {
  test('列表渲染 + 单条已读 + 批量已读 + 清空已读（未读徽标收敛）', async ({ page }) => {
    const ctx = createApiCtx()
    const stores = createStores()
    await seedSession(page)
    await installRouteMocks(page, ctx, stores)
    await page.goto('/notifications')

    // 列表渲染：第 1 页 10 条、恰 1 条未读（id 55，strict 唯一匹配）、徽标 = 全列表未读派生 3
    await expect(page.getByText('通知中心')).toBeVisible()
    const rows = page.locator('.ant-table-tbody tr')
    await expect(rows).toHaveCount(10)
    await expect(page.getByText('未读', { exact: true })).toBeVisible()
    await expect(page.locator('header .ant-badge')).toContainText('3')

    // 单条标已读（第 1 页唯一未读行）：行转已读、徽标 3 → 2
    await page.getByRole('button', { name: '标已读' }).click()
    await expect(page.getByRole('button', { name: '标已读' })).toHaveCount(0)
    await expect(page.getByText('未读', { exact: true })).toHaveCount(0)
    await expect(page.locator('header .ant-badge')).toContainText('2')
    expect(ctx.apiRequests.filter((r) => r === 'PATCH /user/api/notifications/55/read')).toHaveLength(1)

    // 翻第 2 页（id 51/50/49，其中 50/49 未读）→ 勾选两行 → 批量标记已读
    await page.locator('.ant-pagination-item-2').click()
    await expect(rows).toHaveCount(3)
    await rows.nth(1).locator('.ant-checkbox-input').click()
    await rows.nth(2).locator('.ant-checkbox-input').click()
    const batchBtn = page.getByRole('button', { name: '批量标记已读 (2)' })
    await expect(batchBtn).toBeEnabled()
    await batchBtn.click()
    await expect(page.getByText('批量标记已读 2 条')).toBeVisible()
    // 剩余未读清零、徽标收敛至 0（antdv Badge 无 showZero → 计数节点隐藏/不渲染）
    await expect(page.getByRole('button', { name: '标已读' })).toHaveCount(0)
    await expect(page.locator('header .ant-badge-count')).toBeHidden()
    expect(ctx.apiRequests.filter((r) => r === 'PATCH /user/api/notifications/batch-read')).toHaveLength(1)

    // 清空已读（Popconfirm 确认）：13 行已全部转读 → 全部移除，列表空态
    // 注：antdv 两字中文按钮标签自动插空格（清空 → 清 空），故用 \s* 正则匹配
    await page.getByRole('button', { name: '清空已读' }).click()
    await expect(page.locator('.ant-popover')).toBeVisible()
    await page.locator('.ant-popover').getByRole('button', { name: /清\s*空/ }).click()
    await expect(page.getByText('已清空 13 条已读通知')).toBeVisible()
    await expect(page.getByText('暂无通知')).toBeVisible()
    expect(ctx.apiRequests.filter((r) => r === 'DELETE /user/api/notifications/read')).toHaveLength(1)

    assertNoUnhandled(ctx)
    assertNoUnmocked(ctx)
  })
})

// TASK-257 E26：leaderboard 快速切换陈旧响应守卫。本用例落在本文件属白名单约束下的落点选择
// （白名单仅本文件为新建 e2e，见 handoff 登记）；/leaderboard/api/* 不在 helpers 两前缀内，
// 故用例内独立装配 route 与请求监视（零真实网络请求：API 请求仅限 leaderboard 前缀 + 顶栏未读数）。
test.describe('leaderboard 陈旧响应守卫（route-mock）', () => {
  test('首 zset 请求延迟 fulfill、快速切换类型，终态为后发请求数据', async ({ page }) => {
    const apiRequests: string[] = []
    page.on('request', (req) => {
      const p = new URL(req.url()).pathname
      if (/^\/(api|record|user|leaderboard|verify|admin|mapmatch)\//.test(p)) {
        apiRequests.push(`${req.method()} ${p}`)
      }
    })
    await seedSession(page)
    await page.route('**/user/api/notifications/unread-count', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'success', data: 0 }) }),
    )
    const zsetRows: Record<string, unknown[]> = {
      overall: [{ rank: 1, userId: 1, nickname: '总榜甲', distance: 30 }],
      friend: [{ rank: 1, userId: 2, nickname: '好友榜乙', distance: 20 }],
    }
    let friendDelayed = false
    await page.route('**/leaderboard/api/**', async (route) => {
      const req = route.request()
      const url = new URL(req.url())
      const p = url.pathname
      if (p === '/leaderboard/api/leaderboard' && req.method() === 'GET') {
        const type = url.searchParams.get('type') || 'overall'
        if (type === 'friend' && !friendDelayed) {
          // 首个 zset 请求延迟 3s fulfill：制造「慢首请求 + 快后发请求」竞态
          // （挂载初值 lbType=overall，点击已选中单选钮不触发 change，故首击好友榜发起延迟请求）
          friendDelayed = true
          await new Promise((resolve) => setTimeout(resolve, 3000))
        }
        const rows = zsetRows[type] || []
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'success', data: rows }) })
        return
      }
      if (p === '/leaderboard/api/leaderboard/range' && req.method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'success', data: [{ rank: 1, userId: 9, nickname: '今日榜丙', distance: 5 }] }) })
        return
      }
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 9999, message: 'mock unmapped: ' + p }) })
    })

    await page.goto('/leaderboard')
    // 挂载走 range 视图（lbMode 初值 range）
    await expect(page.getByText('今日榜丙')).toBeVisible()

    // 快速切换类型：好友榜（首个 change，zset 请求延迟 3s）→ 总榜（立即）
    await page.locator('.ant-radio-button-wrapper', { hasText: '好友榜' }).click()
    await page.locator('.ant-radio-button-wrapper', { hasText: '总榜' }).click()

    // 后发请求先至：总榜数据落表
    await expect(page.getByText('总榜甲')).toBeVisible()
    // 越过延迟响应到达时点（3s）：陈旧 friend 响应被序号守卫丢弃，终态保持后发数据
    await page.waitForTimeout(3500)
    await expect(page.getByText('总榜甲')).toBeVisible()
    await expect(page.getByText('好友榜乙')).toHaveCount(0)

    expect(apiRequests.filter((r) => r.startsWith('GET /leaderboard/api/leaderboard/range'))).toHaveLength(1)
    expect(apiRequests.filter((r) => r === 'GET /leaderboard/api/leaderboard')).toHaveLength(2)
    const nonLeaderboard = apiRequests.filter(
      (r) => !r.startsWith('GET /leaderboard/api/') && r !== 'GET /user/api/notifications/unread-count',
    )
    expect(nonLeaderboard).toEqual([])
  })
})
