/** @vitest-environment jsdom */

import { act, createElement, useState, type ReactNode } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const navigateMock = vi.fn()
const buttonRecords: Array<{ label: string; onClick?: ((event?: { stopPropagation: () => void }) => void) | undefined }> = []
const useMySkillsMock = vi.fn()
const useSearchMock = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigateMock,
  useLocation: () => ({ pathname: '/dashboard/skills' }),
  useSearch: () => useSearchMock(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
    }),
  }
})

vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => ({ hasRole: () => false }),
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({
    children,
    onClick,
  }: {
    children?: ReactNode
    onClick?: (event?: { stopPropagation: () => void }) => void
  }) => {
    const label = Array.isArray(children) ? children.join('') : String(children ?? '')
    buttonRecords.push({ label, onClick })
    return createElement('button', { onClick }, children)
  },
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: ReactNode }) => createElement('div', null, children),
}))

vi.mock('@/shared/components/empty-state', () => ({
  EmptyState: () => createElement('div', null, 'empty-state'),
}))

vi.mock('@/shared/components/confirm-dialog', () => ({
  ConfirmDialog: () => null,
}))

vi.mock('@/shared/components/dashboard-page-header', () => ({
  DashboardPageHeader: ({ actions }: { actions?: ReactNode }) => createElement('div', null, actions),
}))

vi.mock('@/shared/components/pagination', () => ({
  Pagination: () => null,
}))

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  useArchiveSkill: () => ({ mutateAsync: vi.fn() }),
  useUnarchiveSkill: () => ({ mutateAsync: vi.fn() }),
  useWithdrawSkillReview: () => ({ mutateAsync: vi.fn() }),
}))

vi.mock('@/shared/hooks/use-user-queries', () => ({
  useMySkills: (params: unknown) => useMySkillsMock(params),
  useSubmitPromotion: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useMyNamespaces: () => ({ data: [] }),
}))

vi.mock('@/shared/lib/skill-lifecycle', () => ({
  getHeadlineVersion: () => ({ id: 11, version: '1.0.0', status: 'PUBLISHED' }),
  getPublishedVersion: () => ({ id: 11, version: '1.0.0', status: 'PUBLISHED' }),
  getOwnerPreviewVersion: () => null,
  hasPendingOwnerPreview: () => false,
}))

vi.mock('@/shared/lib/number-format', () => ({
  formatCompactCount: (v: number) => String(v),
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    serverMessageKey?: string
  },
}))

import { MySkillsPage } from './my-skills.tsx'

function findButton(label: string) {
  const record = buttonRecords.find((item) => item.label === label)
  if (!record) {
    throw new Error(`Missing button: ${label}`)
  }
  return record
}

type SearchState = { q?: string; namespace?: string; filter?: string; page?: number }

function renderFilters(initialSearch: SearchState) {
  function RoutedPage() {
    const [search, setSearch] = useState(initialSearch)
    useSearchMock.mockReturnValue(search)
    navigateMock.mockImplementation(({ search: next }: { search: (previous: SearchState) => SearchState }) => {
      setSearch(next)
    })
    return createElement(MySkillsPage)
  }
  return render(createElement(RoutedPage))
}

describe('MySkillsPage', () => {
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  beforeEach(() => {
    navigateMock.mockReset()
    useSearchMock.mockReturnValue({})
    useMySkillsMock.mockClear()
    buttonRecords.length = 0
    useMySkillsMock.mockReturnValue({
      data: {
        items: [
          {
            id: 1,
            displayName: 'Team Agent',
            summary: 'summary',
            namespace: 'team-ai',
            slug: 'team-agent',
            downloadCount: 42,
            status: 'PUBLISHED',
            visibility: 'PRIVATE',
            updatedAt: '2026-08-13T15:04:09',
            canSubmitPromotion: false,
          },
        ],
        total: 1,
        page: 0,
        size: 10,
      },
      isLoading: false,
    })
  })

  it('offers the built-in private namespace even when managed namespaces are empty', () => {
    renderFilters({ namespace: 'private' })

    expect(screen.getByRole('combobox').textContent).toContain('@private')
    expect(useMySkillsMock).toHaveBeenLastCalledWith(expect.objectContaining({ namespace: 'private' }))
  })

  it.each([false, true])('clears all filters without restoring a stale keyword (pending edit: %s)', async (pendingEdit) => {
    vi.useFakeTimers()
    renderFilters({ q: 'agent', namespace: 'private', filter: 'PUBLISHED', page: 2 })
    const input = screen.getByRole('searchbox') as HTMLInputElement
    if (pendingEdit) {
      fireEvent.change(input, { target: { value: 'new keyword' } })
    }

    fireEvent.click(screen.getByRole('button', { name: 'mySkills.clearSearch' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })

    expect(input.value).toBe('')
    expect(screen.getByRole('combobox').textContent).toContain('mySkills.namespaceFilterAll')
    expect(screen.queryByRole('button', { name: 'mySkills.clearSearch' })).toBeNull()
    expect(useMySkillsMock).toHaveBeenLastCalledWith({
      page: 0, size: 10, q: undefined, namespace: undefined, filter: undefined,
    })
    expect(navigateMock).toHaveBeenCalledTimes(1)
  })

  it('clears a status filter when it is the only active filter', () => {
    renderFilters({ filter: 'ARCHIVED', page: 2 })
    fireEvent.click(screen.getByRole('button', { name: 'mySkills.clearSearch' }))

    expect(useMySkillsMock).toHaveBeenLastCalledWith(expect.objectContaining({ filter: undefined, page: 0 }))
  })

  it('debounces typing and resets pagination while retaining the other filters', async () => {
    vi.useFakeTimers()
    renderFilters({ namespace: 'private', filter: 'PUBLISHED', page: 2 })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: ' agent ' } })
    await act(async () => { await vi.advanceTimersByTimeAsync(299) })
    expect(navigateMock).not.toHaveBeenCalled()
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })

    expect(useMySkillsMock).toHaveBeenLastCalledWith({
      page: 0, size: 10, q: 'agent', namespace: 'private', filter: 'PUBLISHED',
    })
  })

  it('accepts URL navigation without writing the previous keyword back', async () => {
    vi.useFakeTimers()
    useSearchMock.mockReturnValue({ q: 'old', page: 2 })
    const view = render(createElement(MySkillsPage))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'pending' } })
    useSearchMock.mockReturnValue({ q: 'restored', namespace: 'private', page: 1 })
    view.rerender(createElement(MySkillsPage))
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })

    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('restored')
    expect(navigateMock).not.toHaveBeenCalled()
    expect(useMySkillsMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'restored', page: 1 }))
  })

  it('navigates to publish page with namespace and visibility when update is clicked', () => {
    renderToStaticMarkup(createElement(MySkillsPage))

    const stopPropagation = vi.fn()
    findButton('sharing.savePrivate').onClick?.({ stopPropagation })

    expect(stopPropagation).toHaveBeenCalledTimes(1)
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/dashboard/publish',
      search: {
        namespace: 'team-ai',
        visibility: 'PRIVATE',
        skillId: 1,
      },
    })
  })

  it('shows last modified time in compact format', () => {
    const html = renderToStaticMarkup(createElement(MySkillsPage))

    expect(html).toContain('mySkills.updatedAt')
    expect(html).toContain('2026-08-13 15:04')
  })

  it('does not render update action for archived skills', () => {
    useMySkillsMock.mockReturnValue({
      data: {
        items: [
          {
            id: 2,
            displayName: 'Archived Agent',
            summary: 'summary',
            namespace: 'team-ai',
            slug: 'archived-agent',
            downloadCount: 7,
            status: 'ARCHIVED',
            visibility: 'PUBLIC',
            canSubmitPromotion: false,
          },
        ],
        total: 1,
        page: 0,
        size: 10,
      },
      isLoading: false,
    })

    renderToStaticMarkup(createElement(MySkillsPage))

    expect(buttonRecords.some((button) => button.label === 'mySkills.update')).toBe(false)
  })

  it('keeps updates private and pinned to the same skill when visibility is missing', () => {
    useMySkillsMock.mockReturnValue({
      data: {
        items: [
          {
            id: 3,
            displayName: 'Default Visibility Agent',
            summary: 'summary',
            namespace: 'team-ai',
            slug: 'default-visibility-agent',
            downloadCount: 9,
            status: 'PUBLISHED',
            canSubmitPromotion: false,
          },
        ],
        total: 1,
        page: 0,
        size: 10,
      },
      isLoading: false,
    })

    renderToStaticMarkup(createElement(MySkillsPage))

    findButton('sharing.savePrivate').onClick?.({ stopPropagation: vi.fn() })

    expect(navigateMock).toHaveBeenCalledWith({
      to: '/dashboard/publish',
      search: {
        namespace: 'team-ai',
        visibility: 'PRIVATE',
        skillId: 3,
      },
    })
  })

  it('exports a named component function', () => {
    expect(typeof MySkillsPage).toBe('function')
  })
})
