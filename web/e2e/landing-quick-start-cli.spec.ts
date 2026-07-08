import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'

test.describe('Landing Quick Start Search Card (Real API)', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
  })

  test('renders agent and human toggle tabs with search card', async ({ page }) => {
    await page.goto('/')

    const agentTab = page.getByRole('button', { name: 'I am Agent', exact: true })
    const humanTab = page.getByRole('button', { name: 'I am Human', exact: true })

    await expect(agentTab).toBeVisible()
    await expect(humanTab).toBeVisible()

    await expect(agentTab).toHaveAttribute('aria-pressed', 'true')

    await humanTab.click()
    await expect(humanTab).toHaveAttribute('aria-pressed', 'true')
    await expect(agentTab).toHaveAttribute('aria-pressed', 'false')

    await expect(page.getByText('npx clawhub search <keyword>', { exact: true })).toBeVisible()

    await agentTab.click()
    await expect(agentTab).toHaveAttribute('aria-pressed', 'true')
    await expect(humanTab).toHaveAttribute('aria-pressed', 'false')
  })

  test('agent tab shows registry prompt with link', async ({ page }) => {
    await page.goto('/')

    const agentTab = page.getByRole('button', { name: 'I am Agent', exact: true })
    await expect(agentTab).toHaveAttribute('aria-pressed', 'true')

    await expect(
      page.getByText(/Read .+\/registry\/skill\.md and follow the instructions/),
    ).toBeVisible()
  })
})
