import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { clearDeletedSkillQueries, isDeleteSlugConfirmationValid, resolveDeletedSkillReturnTo } from './skill-delete-flow.ts'

describe('isDeleteSlugConfirmationValid', () => {
  it('requires an exact slug match', () => {
    expect(isDeleteSlugConfirmationValid('demo-skill', 'demo-skill')).toBe(true)
    expect(isDeleteSlugConfirmationValid('demo-skill', 'Demo-Skill')).toBe(false)
    expect(isDeleteSlugConfirmationValid('demo-skill', 'demo')).toBe(false)
  })
})

describe('resolveDeletedSkillReturnTo', () => {
  it('prefers a safe in-app return path', () => {
    expect(resolveDeletedSkillReturnTo('/dashboard/skills')).toBe('/dashboard/skills')
  })

  it('falls back to search when return path is unsafe or missing', () => {
    expect(resolveDeletedSkillReturnTo('https://example.com')).toBe('/search')
    expect(resolveDeletedSkillReturnTo(undefined)).toBe('/search')
  })
})

describe('clearDeletedSkillQueries', () => {
  it('removes deleted skill detail caches and strips the skill from list caches', () => {
    const queryClient = new QueryClient()
    queryClient.setQueryData(['skills', 'global', 'demo-skill'], { id: 1 })
    queryClient.setQueryData(['skills', 'global', 'demo-skill', 'versions'], [{ version: '1.0.0' }])
    queryClient.setQueryData(['skills', 'global', 'demo-skill', 'versions', '1.0.0', 'files'], [{ id: 1 }])
    queryClient.setQueryData(['skills', 1, 'star'], { starred: true })
    queryClient.setQueryData(['skills', 1, 'rating'], { score: 5, rated: true })
    queryClient.setQueryData(['skills', 'my'], {
      items: [
        { id: 1, slug: 'demo-skill', namespace: 'global', displayName: 'Demo' },
        { id: 2, slug: 'other-skill', namespace: 'global', displayName: 'Other' },
      ],
      total: 2,
      page: 0,
      size: 12,
    })
    queryClient.setQueryData(['skills', 'search', { namespace: 'global', page: 0, size: 20 }], {
      items: [
        { id: 1, slug: 'demo-skill', namespace: 'global', displayName: 'Demo' },
        { id: 3, slug: 'keep-skill', namespace: 'global', displayName: 'Keep' },
      ],
      total: 2,
      page: 0,
      size: 20,
    })

    clearDeletedSkillQueries(queryClient, 'global', 'demo-skill', 1)

    expect(queryClient.getQueryData(['skills', 'global', 'demo-skill'])).toBeUndefined()
    expect(queryClient.getQueryData(['skills', 'global', 'demo-skill', 'versions'])).toBeUndefined()
    expect(queryClient.getQueryData(['skills', 'global', 'demo-skill', 'versions', '1.0.0', 'files'])).toBeUndefined()
    expect(queryClient.getQueryData(['skills', 1, 'star'])).toBeUndefined()
    expect(queryClient.getQueryData(['skills', 1, 'rating'])).toBeUndefined()
    expect(queryClient.getQueryData(['skills', 'my'])).toEqual({
      items: [{ id: 2, slug: 'other-skill', namespace: 'global', displayName: 'Other' }],
      total: 1,
      page: 0,
      size: 12,
    })
    expect(queryClient.getQueryData(['skills', 'search', { namespace: 'global', page: 0, size: 20 }])).toEqual({
      items: [{ id: 3, slug: 'keep-skill', namespace: 'global', displayName: 'Keep' }],
      total: 1,
      page: 0,
      size: 20,
    })
  })
})
