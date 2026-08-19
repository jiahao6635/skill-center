/** @vitest-environment jsdom */
import type { ReactNode } from 'react'
import { act } from 'react'
import { cleanup, render } from '@testing-library/react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const navigateMock = vi.fn()
const useSearchMock = vi.fn()
const buttonRecords: Array<{ label: string; variant?: string | null; onClick?: (() => void) | undefined }> = []
const paginationProps: Array<{ onPageChange: (page: number) => void }> = []
const searchBarProps: Array<{ value?: string; onSearch?: (query: string) => void }> = []
const namespaceFilterProps: Array<{
  value?: string
  onChange?: (slug: string) => void
  isAuthenticated?: boolean
}> = []
const skillCardProps: Array<{
  onNamespaceClick?: (slug: string) => void
}> = []
const searchSkillParams: Array<Record<string, unknown>> = []
const searchSkillOptions: Array<{
  skipGlobalErrorHandler?: boolean
  retry?: (failureCount: number, error: Error) => boolean
} | undefined> = []
const handleApiErrorMock = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigateMock,
  useSearch: () => useSearchMock(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: Record<string, unknown>) => {
        if (options && typeof options.count === 'number') {
          return `${key}:${options.count}`
        }
        return key
      },
    }),
  }
})

vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => ({
    isAuthenticated: true,
  }),
}))

vi.mock('@/features/search/search-bar', () => ({
  SearchBar: (props: { value?: string; onSearch?: (query: string) => void }) => {
    searchBarProps.push(props)
    return <div>search-bar</div>
  },
}))

vi.mock('@/features/search/search-namespace-filter', () => ({
  SearchNamespaceFilter: (props: {
    value?: string
    onChange?: (slug: string) => void
    isAuthenticated?: boolean
  }) => {
    namespaceFilterProps.push(props)
    return <div>search-namespace-filter</div>
  },
}))

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: (props: { onNamespaceClick?: (slug: string) => void }) => {
    skillCardProps.push(props)
    return <div>skill-card</div>
  },
}))

vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => <div>skeleton</div>,
}))

vi.mock('@/shared/components/empty-state', () => ({
  EmptyState: ({ title, description }: { title: string; description?: string }) => (
    <div>
      empty-state
      <span>{title}</span>
      {description ? <span>{description}</span> : null}
    </div>
  ),
}))

vi.mock('@/shared/components/pagination', () => ({
  Pagination: (props: { onPageChange: (page: number) => void }) => {
    paginationProps.push(props)
    return <div>pagination</div>
  },
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({
    children,
    onClick,
    variant,
  }: {
    children?: ReactNode
    onClick?: () => void
    variant?: string
  }) => {
    const label = Array.isArray(children) ? children.join('') : String(children ?? '')
    buttonRecords.push({ label, variant, onClick })
    return <button data-variant={variant}>{children}</button>
  },
}))

vi.mock('@/app/page-shell-style', () => ({
  APP_SHELL_PAGE_CLASS_NAME: 'page-shell',
}))

const useSearchSkillsMock = vi.fn()

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  useSearchSkills: (
    params: Record<string, unknown>,
    options?: {
      skipGlobalErrorHandler?: boolean
      retry?: (failureCount: number, error: Error) => boolean
    },
  ) => {
    searchSkillParams.push(params)
    searchSkillOptions.push(options)
    return useSearchSkillsMock()
  },
}))

vi.mock('@/shared/lib/api-error', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/lib/api-error.ts')>()
  return {
    ...actual,
    handleApiError: (...args: unknown[]) => handleApiErrorMock(...args),
  }
})

vi.mock('@/shared/hooks/use-label-queries', () => ({
  useVisibleLabels: () => ({
    data: [
      { slug: 'code-generation', type: 'RECOMMENDED', displayName: 'Code Generation' },
      { slug: 'official', type: 'RECOMMENDED', displayName: 'Official' },
    ],
  }),
}))

vi.mock('@/shared/hooks/use-user-queries', () => ({
  useMyStars: () => ({
    data: [],
    isLoading: false,
    isFetching: false,
  }),
}))

import { ApiError } from '@/shared/lib/api-error.ts'
import { SearchPage } from './search.tsx'

function findButton(label: string) {
  const record = buttonRecords.find((item) => item.label === label)
  if (!record) {
    throw new Error(`Missing button: ${label}`)
  }
  return record
}

describe('SearchPage', () => {
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  beforeEach(() => {
    navigateMock.mockReset()
    buttonRecords.length = 0
    paginationProps.length = 0
    searchBarProps.length = 0
    namespaceFilterProps.length = 0
    skillCardProps.length = 0
    searchSkillParams.length = 0
    searchSkillOptions.length = 0
    handleApiErrorMock.mockReset()
    useSearchMock.mockReturnValue({
      q: 'agent',
      namespace: 'team-ai',
      label: 'code-generation',
      sort: 'downloads',
      page: 1,
      starredOnly: false,
    })
    useSearchSkillsMock.mockReturnValue({
      data: {
        items: [{ id: 1, displayName: 'Demo Skill', summary: 'summary', namespace: 'global', slug: 'demo', downloadCount: 1, starCount: 1, ratingCount: 0, updatedAt: '2026-03-20T00:00:00Z', canSubmitPromotion: false }],
        total: 24,
        page: 1,
        size: 12,
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
    })
  })

  it('marks the selected label button as active on initial render', () => {
    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('Code Generation')
    expect(findButton('Code Generation').variant).toBe('default')
    expect(findButton('Official').variant).toBe('outline')
  })

  it('toggles the selected label off and resets paging', () => {
    renderToStaticMarkup(<SearchPage />)

    findButton('Code Generation').onClick?.()

    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'team-ai',
        label: '',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('preserves the active label when changing sort', () => {
    renderToStaticMarkup(<SearchPage />)

    findButton('search.sort.newest').onClick?.()

    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'team-ai',
        label: 'code-generation',
        sort: 'newest',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('preserves the active label when paging and when toggling starred-only', () => {
    renderToStaticMarkup(<SearchPage />)

    paginationProps[0]?.onPageChange(2)
    findButton('search.filterStarred').onClick?.()

    expect(navigateMock).toHaveBeenNthCalledWith(1, {
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'team-ai',
        label: 'code-generation',
        sort: 'downloads',
        page: 2,
        starredOnly: false,
      },
    })
    expect(navigateMock).toHaveBeenNthCalledWith(2, {
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'team-ai',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: true,
      },
    })
  })

  it('navigates from the namespace select without splicing q or issuing a second clear', () => {
    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('search-namespace-filter')
    expect(namespaceFilterProps[0]).toMatchObject({
      value: 'team-ai',
      isAuthenticated: true,
    })

    namespaceFilterProps[0]?.onChange?.('acme')

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'acme',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('clears the namespace select while keeping the existing keyword', () => {
    renderToStaticMarkup(<SearchPage />)

    namespaceFilterProps[0]?.onChange?.('')

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: '',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('keeps a selected namespace when the empty discovery box syncs URL state', async () => {
    vi.useFakeTimers()
    useSearchMock.mockReturnValue({
      q: '',
      namespace: '',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: false,
    })

    const { rerender } = render(<SearchPage />)
    namespaceFilterProps[namespaceFilterProps.length - 1]?.onChange?.('acme')

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: '',
        namespace: 'acme',
        label: '',
        sort: 'newest',
        page: 0,
        starredOnly: false,
      },
    })

    useSearchMock.mockReturnValue({
      q: '',
      namespace: 'acme',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: false,
    })
    rerender(<SearchPage />)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(250)
    })

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock.mock.calls.some((call) => call[0]?.search?.namespace === '')).toBe(false)
  })

  it('applies a skill-card namespace without rewriting q', () => {
    renderToStaticMarkup(<SearchPage />)

    skillCardProps[0]?.onNamespaceClick?.('global')

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: 'global',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('toggles the selected namespace off from the skill-card badge', () => {
    renderToStaticMarkup(<SearchPage />)

    skillCardProps[0]?.onNamespaceClick?.('team-ai')

    expect(navigateMock).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'agent',
        namespace: '',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
    })
  })

  it('passes the namespace URL state into skill search', () => {
    renderToStaticMarkup(<SearchPage />)

    expect(searchSkillParams[0]).toMatchObject({
      q: 'agent',
      namespace: 'team-ai',
      label: 'code-generation',
      sort: 'downloads',
      page: 1,
      size: 12,
    })
    expect(searchSkillOptions[0]).toMatchObject({
      skipGlobalErrorHandler: true,
    })
    expect(typeof searchSkillOptions[0]?.retry).toBe('function')
  })

  it('does not retry namespaced 400 search failures', () => {
    renderToStaticMarkup(<SearchPage />)
    const retry = searchSkillOptions[0]?.retry

    expect(retry?.(0, new ApiError('bad', 400))).toBe(false)
    expect(retry?.(0, new ApiError('boom', 500))).toBe(true)
    expect(retry?.(1, new ApiError('boom', 500))).toBe(false)
  })

  it('does not render a duplicate namespace clear chip', () => {
    renderToStaticMarkup(<SearchPage />)

    expect(buttonRecords.some((item) => item.label === 'search.namespaceFilter')).toBe(false)
  })

  it('extracts a leading namespace token from the search input', () => {
    renderToStaticMarkup(<SearchPage />)

    searchBarProps[0]?.onSearch?.('@product-team onboarding')

    expect(navigateMock).toHaveBeenCalledWith({
      to: '/search',
      search: {
        q: 'onboarding',
        namespace: 'product-team',
        label: 'code-generation',
        sort: 'downloads',
        page: 0,
        starredOnly: false,
      },
      replace: true,
    })
  })

  it('renders the default skill list when the empty query still returns items', () => {
    useSearchMock.mockReturnValue({
      q: '',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: false,
    })
    useSearchSkillsMock.mockReturnValue({
      data: {
        items: [{ id: 1, displayName: 'Demo Skill', summary: 'summary', namespace: 'global', slug: 'demo', downloadCount: 1, starCount: 1, ratingCount: 0, updatedAt: '2026-03-20T00:00:00Z', canSubmitPromotion: false }],
        total: 1,
        page: 0,
        size: 12,
      },
      isLoading: false,
      isFetching: false,
    })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('skill-card')
    expect(html).not.toContain('empty-state')
  })

  it('shows a generic empty state when the default discovery list is empty', () => {
    useSearchMock.mockReturnValue({
      q: '',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: false,
    })
    useSearchSkillsMock.mockReturnValue({
      data: {
        items: [],
        total: 0,
        page: 0,
        size: 12,
      },
      isLoading: false,
      isFetching: false,
    })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('empty-state')
    expect(html).toContain('search.noResults')
    expect(html).not.toContain('search.enterKeyword')
  })

  it('shows namespace-scoped empty copy when a keyword has no matches', () => {
    useSearchSkillsMock.mockReturnValue({
      data: {
        items: [],
        total: 0,
        page: 0,
        size: 12,
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
    })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('empty-state')
    expect(html).toContain('search.noResults')
    expect(html).toContain('search.noResultsForInNamespace')
  })

  it('shows namespace-scoped empty copy when a namespace has no visible skills', () => {
    useSearchMock.mockReturnValue({
      q: '',
      namespace: 'team-ai',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: false,
    })
    useSearchSkillsMock.mockReturnValue({
      data: {
        items: [],
        total: 0,
        page: 0,
        size: 12,
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
    })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('empty-state')
    expect(html).toContain('search.noResultsInNamespace')
    expect(html).toContain('search.noResultsInNamespaceHint')
  })

  it('shows namespaceUnavailable for a 400 without toasting', () => {
    useSearchSkillsMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: true,
      error: new ApiError('namespace missing', 400, 'namespace missing', 'namespace missing'),
    })

    const { container } = render(<SearchPage />)

    expect(container.textContent).toContain('search.namespaceUnavailable')
    expect(container.textContent).toContain('search.namespaceUnavailableHint')
    expect(handleApiErrorMock).not.toHaveBeenCalled()
  })

  it('still toasts non-400 search errors when namespace skip is enabled', () => {
    const error = new ApiError('server exploded', 500)
    useSearchSkillsMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: true,
      error,
    })

    render(<SearchPage />)

    expect(handleApiErrorMock).toHaveBeenCalledWith(error)
  })

  it('shows starred empty state for a bad slug while starred-only is on', () => {
    useSearchMock.mockReturnValue({
      q: 'agent',
      namespace: 'does-not-exist',
      label: '',
      sort: 'newest',
      page: 0,
      starredOnly: true,
    })
    useSearchSkillsMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
    })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(searchSkillOptions[0]).toMatchObject({
      skipGlobalErrorHandler: false,
    })
    expect(html).toContain('search.noStarredResults')
    expect(html).toContain('search.noStarredResultsFor')
    expect(html).not.toContain('search.namespaceUnavailable')
  })
})
