import { expect, test, type Page } from '@playwright/test'

if (process.env.SKILL_USAGE_BASE_URL) test.use({ baseURL: process.env.SKILL_USAGE_BASE_URL })

if (process.env.SKILL_USAGE_BROWSER_EXECUTABLE) {
  test.use({ launchOptions: { executablePath: process.env.SKILL_USAGE_BROWSER_EXECUTABLE } })
}

// UI contract fixtures only. Database semantics and real HTTP permissions are covered separately
// by SkillUsageStatsPostgresTest and the controller tests; no production data is read or written.
async function fixture(page: Page, role = 'SUPER_ADMIN') {
  let revoked = false
  await page.addInitScript(() => {
    localStorage.setItem('i18nextLng', 'zh')
    // Notifications are outside this fixture; keep their SSE connection inert rather than
    // returning JSON to an EventSource and exercising unrelated reconnect behavior.
    class FixtureEventSource extends EventTarget { close() {} }
    Object.defineProperty(window, 'EventSource', { value: FixtureEventSource })
  })
  const names = ['data-tools:daily-report', 'code-review', 'lark-doc', 'team:sql-analysis', 'ui-ux-pro-max', 'local-research-skill']
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (!path.startsWith('/api/')) { await route.continue(); return }
    let data: unknown = []
    if (path.endsWith('/auth/me')) data = { userId: 'preview-admin', displayName: '管理员', email: 'admin@example.test', platformRoles: [role] }
    else if (path.includes('/skill-invocations')) {
      if (revoked || role !== 'SUPER_ADMIN') {
        await route.fulfill({ status: 403, json: { code: 403, msg: 'Forbidden' } }); return
      }
      if (path.endsWith('/summary')) data = { invocationCount: 1268, userCount: 36, skillCount: 24, sessionCount: 182, queriedAt: '2026-09-14T07:00:00Z' }
      else if (path.endsWith('/skills')) data = { items: names.map((skillName, i) => ({ skillName, invocationCount: url.searchParams.has('email') ? 20 - i : 428 - i * 67, userCount: 26 - i * 3, rank: i + 1, peakCount: 428, lastUsedAt: '2026-09-14T06:32:00Z', unlinked: i > 3, downloadCount: i > 3 ? null : 1520 - i * 312, starCount: i > 3 ? null : 58 - i * 9 })), total: 6, page: 0, size: 20 }
      else if (path.endsWith('/users')) data = { items: [{ email: 'lin@example.test', name: '林同学', invocationCount: 218, skillCount: 12, lastUsedAt: '2026-09-14T06:32:00Z', rank: 1 }], total: 1, page: 0, size: 20 }
      else if (path.endsWith('/user-options')) data = [{ email: 'lin@example.test', name: '林同学' }, { email: 'lin2@example.test', name: '林同学' }]
      else data = { items: [1, 2, 3].map(id => ({ id, event: { email: 'lin@example.test', name: '林同学', skill_name: url.searchParams.get('skillName'), session_id: `session-preview-${id}`, occurred_at: '2026-09-14T06:32:00Z', client_product: 'qoder_ide', trigger_mode: id === 2 ? 'manual' : 'automatic' } })), total: 3, page: 0, size: 20 }
    } else if (path.includes('unread-count')) data = { count: 0 }
    else if (path.includes('notifications')) data = { items: [], total: 0, page: 0, size: 20 }
    await route.fulfill({ json: { code: 0, data, msg: 'ok' } })
  })
  return { revoke: () => { revoked = true } }
}

test('dashboard, user filtering, browser history, modal focus and revoked access', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.stack || error.message))
  const controls = await fixture(page)
  await page.setViewportSize({ width: 1440, height: 1100 })
  await page.goto('/admin/skill-usage')
  await expect(page.getByRole('heading', { name: 'Skill 使用统计' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'data-tools:daily-report' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '累计下载' })).toBeVisible()
  await page.screenshot({ path: '/tmp/skill-usage-desktop.png', fullPage: true })
  await page.getByRole('button', { name: '用户排行', exact: true }).click()
  await page.getByRole('button', { name: '查看使用情况' }).filter({ visible: true }).click()
  await expect(page.getByRole('heading', { name: '该用户使用的 Skill' })).toBeVisible()
  await expect(page).toHaveURL(/email=lin%40example.test/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '该用户使用的 Skill' })).toBeVisible()
  const opener = page.getByRole('button', { name: '查看明细' }).filter({ visible: true }).first()
  await opener.click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('session-preview-1')).toBeVisible()
  await page.screenshot({ path: '/tmp/skill-usage-details.png', fullPage: true })
  for (let i = 0; i < 12; i++) await page.keyboard.press('Tab')
  expect(await page.evaluate(() => document.querySelector('dialog')?.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(opener).toBeFocused()
  await page.getByRole('button', { name: '清除用户筛选' }).click()
  await page.goBack()
  await expect(page.getByRole('heading', { name: '该用户使用的 Skill' })).toBeVisible()
  controls.revoke()
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '无权访问' })).toBeVisible()
  await expect(page.getByText('data-tools:daily-report')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('mobile and dark layouts, user search with duplicate names', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/admin/skill-usage')
  await expect(page.getByRole('heading', { name: 'Skill 使用统计' })).toBeVisible()
  await expect(page.getByText('data-tools:daily-report').filter({ visible: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '/tmp/skill-usage-mobile.png', fullPage: true })
  const picker = page.getByRole('combobox', { name: '用户', exact: true })
  await picker.fill('林')
  await expect(page.getByRole('option', { name: /林同学/ })).toHaveCount(2)
  await picker.press('ArrowDown'); await picker.press('ArrowDown'); await picker.press('Enter')
  await expect(page).toHaveURL(/email=lin2%40example.test/)
  await page.getByRole('button', { name: '查看明细' }).filter({ visible: true }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect((await page.getByRole('dialog').boundingBox())?.width).toBe(390)
  await page.keyboard.press('Escape')
  await page.setViewportSize({ width: 1440, height: 1100 })
  await page.evaluate(() => { document.documentElement.classList.add('dark'); window.scrollTo({ top: 0, behavior: 'instant' }) })
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--background').trim())).toBe('222 47% 6%')
  await page.screenshot({ path: '/tmp/skill-usage-dark.png', fullPage: true })
})

test('auditor cannot see the entry or open the page', async ({ page }) => {
  await fixture(page, 'AUDITOR')
  await page.goto('/dashboard')
  await expect(page.getByRole('link', { name: 'Skill Center', exact: true })).toBeVisible()
  await page.goto('/admin/skill-usage')
  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByRole('heading', { name: 'Skill 使用统计' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Skill 使用统计' })).toHaveCount(0)
})
