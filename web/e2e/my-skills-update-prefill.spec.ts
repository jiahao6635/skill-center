import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { registerSession } from './helpers/session'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

test.describe('My Skills Update Prefill (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await registerSession(page, testInfo)
  })

  test('opens a private update pinned to the original skill from my skills card', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.ensureWritableNamespace()
      const skillName = `update-ui-${Date.now().toString(36)}`
      await builder.publishSkill(namespace.slug, { name: skillName })

      await page.goto('/dashboard/skills')
      await expect(page.getByRole('heading', { name: 'My Skills' })).toBeVisible()

      const skillCard = page.locator('.group').filter({
        has: page.getByRole('heading', { name: skillName, exact: true }),
      }).first()
      await expect(skillCard).toBeVisible()
      await skillCard.getByRole('button', { name: 'Save private version' }).click()

      await expect(page).toHaveURL(/\/dashboard\/publish/)

      const currentUrl = new URL(page.url())
      expect(currentUrl.searchParams.get('namespace')).toBe(namespace.slug)
      expect(currentUrl.searchParams.get('visibility')).toBe('PRIVATE')
      expect(Number(currentUrl.searchParams.get('skillId'))).toBeGreaterThan(0)
      await expect(page.locator('#namespace')).toHaveCount(0)
      await expect(page.locator('#visibility')).toContainText('Private')
    } finally {
      await builder.cleanup()
    }
  })

  test('falls back to public visibility when publish search params are invalid', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.ensureWritableNamespace()

      await page.goto(`/dashboard/publish?namespace=${encodeURIComponent(namespace.slug)}&visibility=internal`)
      await expect(page.getByRole('heading', { name: 'Publish Skill' })).toBeVisible()
      await expect(page.locator('#namespace')).toContainText(`@${namespace.slug}`)
      await expect(page.locator('#visibility')).toContainText('Public')
    } finally {
      await builder.cleanup()
    }
  })
})
