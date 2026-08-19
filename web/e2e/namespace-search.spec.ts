import { expect, test, type Page } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

function waitForSkillSearch(page: Page, options: { namespace?: string; q?: string; sort?: string }) {
  return page.waitForResponse((response) => {
    if (!response.ok() || !response.url().includes('/api/web/skills?')) {
      return false
    }

    const url = new URL(response.url())
    const namespace = url.searchParams.get('namespace') ?? ''
    const query = url.searchParams.get('q') ?? ''
    const sort = url.searchParams.get('sort') ?? ''

    return namespace === (options.namespace ?? '')
      && query === (options.q ?? '')
      && (!options.sort || sort === options.sort)
  })
}

function namespaceFilter(page: Page) {
  return page.getByLabel('Filter by namespace')
}

async function chooseNamespaceOption(page: Page, name: string | RegExp, exact = false) {
  await namespaceFilter(page).click()
  const option = page.getByRole('option', { name, exact })
  await expect(option).toBeVisible()
  await option.click()
}

test.describe('Namespace Search (Real API)', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
    await page.context().setExtraHTTPHeaders({
      'X-Mock-User-Id': 'local-admin',
    })
  })

  test('submits @namespace keyword search and clears the namespace filter', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-pm-search')
      const otherNamespace = await builder.createNamespace('e2e-dev-search')
      const namespaceSkill = await builder.publishSkill(namespace.slug, {
        name: 'roadmap-discovery',
        description: 'Roadmap planning skill for namespace search regression.',
      })
      const otherSkill = await builder.publishSkill(otherNamespace.slug, {
        name: 'roadmap-backend',
        description: 'Roadmap planning skill outside the selected namespace.',
      })
      await builder.waitForSearchResults('roadmap', [namespaceSkill.slug, otherSkill.slug])

      await page.goto('/search')
      await page.getByPlaceholder('Search skills...').fill(`@${namespace.slug} roadmap`)

      const filteredSearch = waitForSkillSearch(page, { namespace: namespace.slug, q: 'roadmap' })
      await page.getByRole('button', { name: 'Search', exact: true }).click()
      await filteredSearch

      await expect(page).toHaveURL(new RegExp(`namespace=${namespace.slug}`))
      await expect(page).toHaveURL(/q=roadmap/)
      await expect(namespaceFilter(page)).toContainText(`@${namespace.slug}`)
      await expect(page.getByRole('heading', { name: namespaceSkill.slug })).toBeVisible()
      await expect(page.getByText(`@${otherNamespace.slug}`)).toHaveCount(0)

      await page.goto(`/search?q=roadmap&namespace=${namespace.slug}&sort=downloads&page=1&starredOnly=false`)
      await expect(namespaceFilter(page)).toContainText(`@${namespace.slug}`)

      const unfilteredSearch = waitForSkillSearch(page, { q: 'roadmap', sort: 'downloads' })
      await chooseNamespaceOption(page, 'All namespaces', true)
      await unfilteredSearch

      await expect(page).toHaveURL(/q=roadmap/)
      await expect(page).toHaveURL(/sort=downloads/)
      await expect(page).toHaveURL(/page=0/)
      await expect(page).not.toHaveURL(new RegExp(`namespace=${namespace.slug}`))
      await expect(page.getByRole('heading', { name: namespaceSkill.slug })).toBeVisible()
      await expect(page.getByRole('heading', { name: otherSkill.slug })).toBeVisible()
    } finally {
      await builder.cleanup()
    }
  })

  test('supports a sixty-four character namespace slug in search input', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-namespace-64-slug-search-case-alphaab')
      expect(namespace.slug).toHaveLength(64)
      const skill = await builder.publishSkill(namespace.slug, {
        name: 'boundary-search-agent',
        description: 'Boundary namespace search regression skill.',
      })
      await builder.waitForSearchResult('boundary', skill.slug)

      await page.goto('/search')
      await page.getByPlaceholder('Search skills...').fill(`@${namespace.slug} boundary`)

      const filteredSearch = waitForSkillSearch(page, { namespace: namespace.slug, q: 'boundary' })
      await page.getByRole('button', { name: 'Search', exact: true }).click()
      await filteredSearch

      await expect(page).toHaveURL(new RegExp(`namespace=${namespace.slug}`))
      await expect(page).toHaveURL(/q=boundary/)
      await expect(namespaceFilter(page)).toContainText(`@${namespace.slug}`)
      await expect(page.getByRole('heading', { name: skill.slug })).toBeVisible()
    } finally {
      await builder.cleanup()
    }
  })

  test('selecting a namespace from the filter hides skills from other namespaces', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-select-ns')
      const otherNamespace = await builder.createNamespace('e2e-select-other')
      const namespaceSkill = await builder.publishSkill(namespace.slug, {
        name: 'select-filter-alpha',
        description: 'Skill used to assert namespace select filtering.',
      })
      const otherSkill = await builder.publishSkill(otherNamespace.slug, {
        name: 'select-filter-beta',
        description: 'Skill that should disappear after namespace select.',
      })
      await builder.waitForSearchResults('select-filter', [namespaceSkill.slug, otherSkill.slug])

      await page.goto('/search?q=select-filter')
      await expect(page.getByRole('heading', { name: namespaceSkill.slug })).toBeVisible()
      await expect(page.getByRole('heading', { name: otherSkill.slug })).toBeVisible()

      const filteredSearch = waitForSkillSearch(page, { namespace: namespace.slug, q: 'select-filter' })
      await chooseNamespaceOption(page, `@${namespace.slug}`)
      await filteredSearch

      await expect(page).toHaveURL(new RegExp(`namespace=${namespace.slug}`))
      await expect(page.getByRole('heading', { name: namespaceSkill.slug })).toBeVisible()
      await expect(page.getByRole('heading', { name: otherSkill.slug })).toHaveCount(0)
    } finally {
      await builder.cleanup()
    }
  })

  test('clicking a card namespace badge writes then clears the filter', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-badge-ns')
      const skill = await builder.publishSkill(namespace.slug, {
        name: 'badge-toggle-skill',
        description: 'Skill used to assert namespace badge toggle.',
      })
      await builder.waitForSearchResult('badge-toggle', skill.slug)

      await page.goto(`/search?q=${encodeURIComponent(skill.slug)}`)
      await expect(page.getByRole('heading', { name: skill.slug })).toBeVisible()

      const applyFilter = waitForSkillSearch(page, { namespace: namespace.slug, q: skill.slug })
      await page.getByRole('button', { name: `Filter namespace @${namespace.slug}` }).click()
      await applyFilter

      await expect(page).toHaveURL(new RegExp(`namespace=${namespace.slug}`))
      await expect(namespaceFilter(page)).toContainText(`@${namespace.slug}`)

      const clearFilter = waitForSkillSearch(page, { q: skill.slug })
      await page.getByRole('button', { name: `Filter namespace @${namespace.slug}` }).click()
      await clearFilter

      await expect(page).not.toHaveURL(new RegExp(`namespace=${namespace.slug}`))
    } finally {
      await builder.cleanup()
    }
  })

  test('illegal namespace shows the unavailable empty state without a toast', async ({ page }) => {
    const unavailableSearch = page.waitForResponse((response) => {
      return response.url().includes('/api/web/skills?')
        && response.status() === 400
        && (new URL(response.url()).searchParams.get('namespace') === 'this-namespace-does-not-exist-zzzzz')
    })
    await page.goto('/search?namespace=this-namespace-does-not-exist-zzzzz')
    await unavailableSearch

    await expect(page.getByRole('heading', { name: 'This namespace is not available' })).toBeVisible()
    await expect(page.getByText(/It may not exist, is archived, or you do not have access/)).toBeVisible()
    await expect(page).toHaveURL(/namespace=this-namespace-does-not-exist-zzzzz/)
    await expect(namespaceFilter(page)).toContainText('@this-namespace-does-not-exist-zzzzz')
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0)
  })
})

test.describe('Namespace Search (Anonymous)', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
  })

  test('anonymous dropdown only lists All namespaces and @global', async ({ page }) => {
    await page.goto('/search')
    await namespaceFilter(page).click()

    await expect(page.getByRole('option', { name: 'All namespaces', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: /@global/ })).toBeVisible()
    await expect(page.getByRole('option')).toHaveCount(2)
  })

  test('anonymous dropdown includes the current URL slug when present', async ({ page }) => {
    await page.goto('/search?namespace=orphan-space-from-url')
    await namespaceFilter(page).click()

    await expect(page.getByRole('option', { name: 'All namespaces', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: /@global/ })).toBeVisible()
    await expect(page.getByRole('option', { name: /@orphan-space-from-url/ })).toBeVisible()
    await expect(page.getByRole('option')).toHaveCount(3)
  })
})
