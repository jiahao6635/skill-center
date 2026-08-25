import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import {
  getSearchCards,
  prepareSearchSeed,
  type PreparedSearchSeed,
} from './helpers/search-seed'
import { loginWithCredentials } from './helpers/session'

test.setTimeout(180_000)

let seed: PreparedSearchSeed | undefined

test.beforeAll(async ({ browser }, testInfo) => {
  seed = await prepareSearchSeed(browser, testInfo, { count: 1 })
})

test.afterAll(async () => {
  await seed?.dispose()
  seed = undefined
})

test.describe('Author filter (Real API)', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
  })

  test('filters by the live publisher display name and round-trips ?author=', async ({ page }) => {
    const displayName = seed!.publisherDisplayName
    const authorInput = page.getByLabel('Filter by author')

    await page.goto('/search?sort=newest&page=0&starredOnly=false')
    await expect(getSearchCards(page).first()).toBeVisible({ timeout: 15_000 })

    await authorInput.fill(displayName)
    await authorInput.press('Enter')
    await expect.poll(() => new URL(page.url()).searchParams.get('author')).toBe(displayName)
    await expect(getSearchCards(page).filter({ hasText: seed!.skillNames[0] })).toHaveCount(1, { timeout: 15_000 })

    if (displayName.length > 1) {
      await authorInput.fill(displayName.slice(0, Math.max(1, displayName.length - 1)))
      await authorInput.press('Enter')
      await expect(page.getByText(/No visible skills by/)).toBeVisible({ timeout: 10_000 })
    }

    await page.getByLabel('Clear author filter').click()
    await expect.poll(() => new URL(page.url()).searchParams.get('author')).toBeFalsy()
    await expect(getSearchCards(page).first()).toBeVisible({ timeout: 15_000 })

    await page.goto(`/search?author=${encodeURIComponent(displayName)}&sort=newest&page=0&starredOnly=false`)
    await expect(authorInput).toHaveValue(displayName)
    await expect(getSearchCards(page).filter({ hasText: seed!.skillNames[0] })).toHaveCount(1, { timeout: 15_000 })
  })

  test('combines author with namespace', async ({ page }) => {
    const displayName = seed!.publisherDisplayName
    await page.goto(
      `/search?author=${encodeURIComponent(displayName)}&namespace=${encodeURIComponent(seed!.namespace.slug)}&sort=newest&page=0&starredOnly=false`,
    )
    await expect(getSearchCards(page).filter({ hasText: seed!.skillNames[0] })).toHaveCount(1, { timeout: 15_000 })
  })

  test('does not call portal search when starred-only is on', async ({ page }, testInfo) => {
    const username = process.env.E2E_PUBLISH_USERNAME
    const password = process.env.E2E_PUBLISH_PASSWORD
    test.skip(!username || !password, 'Requires E2E_PUBLISH_USERNAME / E2E_PUBLISH_PASSWORD')

    await loginWithCredentials(page, { username: username!, password: password! }, testInfo)
    const displayName = seed!.publisherDisplayName
    let portalSearchCalls = 0
    page.on('request', (request) => {
      if (request.url().includes('/api/web/skills?')) {
        portalSearchCalls += 1
      }
    })

    await page.goto(
      `/search?author=${encodeURIComponent(displayName)}&sort=newest&page=0&starredOnly=true`,
    )
    await expect(page.getByRole('button', { name: 'Starred only' })).toBeVisible()
    expect(portalSearchCalls).toBe(0)
  })
})
