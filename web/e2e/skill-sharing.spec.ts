import { expect, test, type Page } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import type { components } from '../src/api/generated/schema'

type ShareRequest = components['schemas']['SkillShareResponse']
type Command = components['schemas']['SkillShareCommand']

async function mockSharing(page: Page, initial?: ShareRequest, shared = false) {
  let latestRequest = initial
  const commands: Command[] = []
  let conflict = false
  const skill = {
    id: 42, slug: 'weekly-report', displayName: 'Weekly report', namespace: shared ? 'data-team' : 'private',
    ownerId: 'author', visibility: shared ? 'NAMESPACE_ONLY' : 'PRIVATE', status: 'ACTIVE', hidden: false,
    canManageLifecycle: true, canSubmitPromotion: false, downloadCount: 0, starCount: 0, ratingCount: 0,
    headlineVersion: { id: 102, version: '1.0.0', status: 'UPLOADED' },
    ownerPreviewVersion: { id: 102, version: '1.0.0', status: 'UPLOADED' },
  }
  await setEnglishLocale(page)
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'sharing-csrf', url: 'http://127.0.0.1:3000' }])
  await page.route(/\/api\/(v1|web)\//, async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = []
    if (path.endsWith('/auth/me')) data = { userId: 'author', displayName: 'Author', platformRoles: [] }
    else if (path.endsWith('/me/skills')) data = { items: [skill], total: 1, page: 0, size: 12 }
    else if (path.endsWith('/me/namespaces')) data = [{ id: 2, slug: 'data-team', displayName: 'Data team', type: 'TEAM', status: 'ACTIVE' }]
    else if (path.endsWith('/sharing/precheck')) {
      expect(route.request().headers()['x-xsrf-token']).toBe('sharing-csrf')
      data = { valid: !conflict, errors: conflict ? ['sharing.nameConflict'] : [], warnings: [] }
    } else if (path.endsWith('/sharing') && route.request().method() === 'POST') {
      const command = route.request().postDataJSON() as Command
      commands.push(command)
      expect(route.request().headers()['x-xsrf-token']).toBe('sharing-csrf')
      latestRequest = {
        id: 9, status: 'SCANNING', versionId: command.versionId, version: '1.0.0',
        targetNamespaceId: command.targetNamespaceId, targetNamespace: command.targetNamespaceId === 1 ? 'global' : 'data-team',
        targetDisplayName: command.targetNamespaceId === 1 ? 'Global' : 'Data team', targetVisibility: command.targetVisibility,
      }
      data = latestRequest
    } else if (path.endsWith('/withdraw')) { latestRequest = { ...latestRequest, status: 'WITHDRAWN' }; data = latestRequest }
    else if (path.endsWith('/sharing')) data = {
      skillId: 42, slug: 'weekly-report', namespace: skill.namespace, visibility: skill.visibility, latestRequest,
      versions: [{ id: 102, version: '1.0.0', status: 'UPLOADED', fileCount: 3 }, { id: 101, version: '0.9.0', status: 'PUBLISHED', fileCount: 2 }],
      targets: [{ id: 2, slug: 'data-team', displayName: 'Data team', type: 'TEAM' }, ...(!shared ? [{ id: 1, slug: 'global', displayName: 'Global', type: 'GLOBAL' }] : [])],
    }
    await route.fulfill({ json: { code: 0, msg: 'ok', data } })
  })
  await page.goto('/dashboard/skills')
  await page.getByRole('button', { name: shared ? 'Update shared version' : 'Publish and share', exact: true }).click()
  return {
    commands,
    setConflict(value: boolean) { conflict = value },
    complete() { latestRequest = { ...latestRequest, status: 'COMPLETED' } },
  }
}

test('shares a selected existing version and shows a permanent link after completion', async ({ page }) => {
  const state = await mockSharing(page)
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Target space', { exact: true }).selectOption('2')
  await dialog.getByRole('button', { name: 'Check and continue' }).click()
  await expect(dialog.getByText('Checks passed. Confirm sharing details.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Submit sharing request' }).click()
  await expect(dialog.getByText('Security scan in progress')).toBeVisible()
  expect(state.commands).toHaveLength(1)
  expect(state.commands[0]).toMatchObject({ versionId: 102, targetNamespaceId: 2, targetVisibility: 'NAMESPACE_ONLY' })
  state.complete()
  await expect(dialog.getByText('Shared successfully', { exact: true })).toBeVisible({ timeout: 10_000 })
  await expect(dialog.getByRole('link', { name: 'View skill' })).toHaveAttribute('href', '/skills/by-id/42')
})

test('requires explicit public confirmation and keeps that choice in the request', async ({ page }) => {
  const state = await mockSharing(page)
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Target space', { exact: true }).selectOption('1')
  await dialog.getByLabel('Who can access', { exact: true }).selectOption('PUBLIC')
  await expect(dialog.getByRole('button', { name: 'Check and continue' })).toBeDisabled()
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: 'Check and continue' }).click()
  await dialog.getByRole('button', { name: 'Submit sharing request' }).click()
  await expect(dialog.getByText('Security scan in progress')).toBeVisible()
  expect(state.commands[0]).toMatchObject({ targetVisibility: 'PUBLIC', confirmPublic: true })
})

test('reopens pending sharing, traps keyboard focus and allows withdrawal', async ({ page }) => {
  await mockSharing(page, { id: 9, status: 'PENDING_REVIEW', versionId: 102, version: '1.0.0', targetNamespace: 'data-team', targetDisplayName: 'Data team' })
  let dialog = page.getByRole('dialog')
  await expect(dialog.getByText('Waiting for target-space review')).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Check and continue' })).toHaveCount(0)
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: 'Close', exact: true })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('button', { name: 'Withdraw sharing request' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Publish and share', exact: true })).toBeFocused()
  await page.getByRole('button', { name: 'Publish and share', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Withdraw sharing request' }).click()
  await expect(dialog.getByText('Sharing withdrawn', { exact: true })).toBeVisible()
})

test('blocks name conflicts without submitting or losing the selected version', async ({ page }) => {
  const state = await mockSharing(page)
  state.setConflict(true)
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Version to share').selectOption('101')
  await dialog.getByLabel('Target space', { exact: true }).selectOption('2')
  await dialog.getByRole('button', { name: 'Check and continue' }).click()
  await expect(dialog.getByText('The target space already contains this skill name. Choose another space.')).toBeVisible()
  expect(state.commands).toHaveLength(0)
  await expect(dialog.getByLabel('Version to share')).toHaveValue('101')
  state.setConflict(false)
  await dialog.getByRole('button', { name: 'Check again' }).click()
  await expect(dialog.getByRole('button', { name: 'Submit sharing request' })).toBeEnabled()
})

test('fits the sharing controls on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 })
  await mockSharing(page)
  const dialog = page.getByRole('dialog')
  const bounds = await dialog.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(320)
  await dialog.getByLabel('Target space', { exact: true }).selectOption('2')
  await dialog.getByRole('button', { name: 'Check and continue' }).click()
  await expect(dialog.getByRole('button', { name: 'Submit sharing request' })).toBeVisible()
})

test('updates an already shared skill in the same space and returns to its success summary', async ({ page }) => {
  const state = await mockSharing(page, { id: 8, status: 'COMPLETED', version: '0.8.0', targetNamespace: 'data-team', targetDisplayName: 'Data team' }, true)
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Version to share')).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Update shared version', exact: true }).click()
  await expect(dialog.getByLabel('Target space', { exact: true })).toBeDisabled()
  await expect(dialog.getByLabel('Target space', { exact: true })).toHaveValue('2')
  await dialog.getByRole('button', { name: 'Check and continue' }).click()
  await dialog.getByRole('button', { name: 'Submit sharing request' }).click()
  await expect(dialog.getByText('Security scan in progress')).toBeVisible()
  expect(state.commands[0]).toMatchObject({ versionId: 102, targetNamespaceId: 2, targetVisibility: 'NAMESPACE_ONLY' })
  state.complete()
  await expect(dialog.getByText('Shared successfully', { exact: true })).toBeVisible({ timeout: 10_000 })
  await expect(dialog.getByLabel('Version to share')).toHaveCount(0)
})
