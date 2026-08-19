import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '@/i18n/config.ts'
import {
  getAdminLabelDefinitionsQueryKey,
  getSkillDetailQueryKey,
  getSkillLabelsQueryKey,
  getVisibleLabelsQueryKey,
} from './query-keys.ts'

const useQueryMock = vi.fn((options: unknown) => options)

vi.mock('@tanstack/react-query', () => ({
  useQuery: (options: unknown) => useQueryMock(options),
  useMutation: vi.fn(),
  useQueryClient: vi.fn(),
}))

vi.mock('@/api/client.ts', () => ({
  fetchJson: vi.fn(),
  fetchText: vi.fn(),
  getCsrfHeaders: vi.fn(() => ({})),
  skillLifecycleApi: {},
  WEB_API_PREFIX: '/api/web',
}))

describe('localized label query keys', () => {
  const originalLanguage = i18n.language
  const originalResolvedLanguage = i18n.resolvedLanguage

  afterEach(() => {
    i18n.language = originalLanguage
    i18n.resolvedLanguage = originalResolvedLanguage
  })

  it('includes the current language so localized label data refetches after language switches', () => {
    i18n.language = 'en'
    i18n.resolvedLanguage = 'en'

    expect(getVisibleLabelsQueryKey()).toEqual(['labels', 'visible', 'en'])
    expect(getSkillLabelsQueryKey('team', 'demo')).toEqual(['labels', 'skill', 'team', 'demo', 'en'])
    expect(getSkillDetailQueryKey('team', 'demo')).toEqual(['skills', 'team', 'demo', 'en'])
    expect(getAdminLabelDefinitionsQueryKey()).toEqual(['labels', 'admin', 'en'])

    i18n.language = 'zh-CN'
    i18n.resolvedLanguage = 'zh-CN'

    expect(getVisibleLabelsQueryKey()).toEqual(['labels', 'visible', 'zh-CN'])
    expect(getSkillLabelsQueryKey('team', 'demo')).toEqual(['labels', 'skill', 'team', 'demo', 'zh-CN'])
    expect(getSkillDetailQueryKey('team', 'demo')).toEqual(['skills', 'team', 'demo', 'zh-CN'])
    expect(getAdminLabelDefinitionsQueryKey()).toEqual(['labels', 'admin', 'zh-CN'])
  })
})

describe('useSearchSkills options bag', () => {
  beforeEach(() => {
    useQueryMock.mockClear()
  })

  it('keeps default query options when no bag is passed', async () => {
    const { useSearchSkills } = await import('./use-skill-queries.ts')
    useSearchSkills({ q: 'agent' })

    const queryOptions = useQueryMock.mock.calls[0]?.[0] as {
      queryKey: unknown
      enabled: boolean
      retry?: unknown
      meta?: unknown
    }

    expect(useQueryMock).toHaveBeenCalledTimes(1)
    expect(queryOptions).toMatchObject({
      queryKey: ['skills', 'search', { q: 'agent' }],
      enabled: true,
    })
    expect(queryOptions).not.toHaveProperty('meta')
    expect(queryOptions.retry).toBeUndefined()
  })

  it('forwards skipGlobalErrorHandler and retry from the options bag', async () => {
    const { useSearchSkills } = await import('./use-skill-queries.ts')
    const retry = vi.fn()
    useSearchSkills(
      { q: 'agent', namespace: 'acme' },
      { skipGlobalErrorHandler: true, retry },
    )

    expect(useQueryMock.mock.calls[0]?.[0]).toMatchObject({
      enabled: true,
      retry,
      meta: { skipGlobalErrorHandler: true },
    })
  })

  it('disables search while starred-only is on', async () => {
    const { useSearchSkills } = await import('./use-skill-queries.ts')
    useSearchSkills({ q: 'agent', starredOnly: true })

    expect(useQueryMock.mock.calls[0]?.[0]).toMatchObject({
      enabled: false,
    })
  })
})
