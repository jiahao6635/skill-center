import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const useSearchMock = vi.fn()
const selectRecords: Array<{ value?: string }> = []
const selectItemRecords: Array<{ value?: string; description?: unknown; children?: unknown }> = []

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
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

vi.mock('@/features/publish/upload-zone', () => ({
  UploadZone: () => null,
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/select', () => ({
  Select: ({ children, value }: { children: unknown; value?: string }) => {
    selectRecords.push({ value })
    return children
  },
  SelectContent: ({ children }: { children: unknown }) => children,
  SelectItem: ({ children, value, description }: { children: unknown; value?: string; description?: unknown }) => {
    selectItemRecords.push({ value, description, children })
    return children
  },
  SelectTrigger: ({ children }: { children: unknown }) => children,
  SelectValue: () => null,
  normalizeSelectValue: (v: string) => v || null,
}))

vi.mock('@/shared/ui/label', () => ({
  Label: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  usePublishSkill: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useMyNamespaces: () => ({ data: [], isLoading: false }),
}))

vi.mock('@/shared/components/dashboard-page-header', () => ({
  DashboardPageHeader: () => null,
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    serverMessageKey?: string
  },
}))

import { PublishPage } from './publish.tsx'

describe('PublishPage', () => {
  beforeEach(() => {
    selectRecords.length = 0
    selectItemRecords.length = 0
    useSearchMock.mockReturnValue({
      namespace: '  team-ai  ',
      visibility: 'private',
    })
  })

  it('prefills namespace and visibility from route search params', () => {
    renderToStaticMarkup(createElement(PublishPage))

    expect(selectRecords[0]?.value).toBe('private')
    expect(selectRecords[1]?.value).toBe('PRIVATE')
  })

  it('falls back to public visibility when search params are missing', () => {
    useSearchMock.mockReturnValue({})

    renderToStaticMarkup(createElement(PublishPage))

    expect(selectRecords[0]?.value).toBe('__select_namespace__')
    expect(selectRecords[1]?.value).toBe('PUBLIC')
  })

  it('exports a named component function', () => {
    expect(typeof PublishPage).toBe('function')
  })

  it('keeps visibility descriptions on the option instead of the trigger label', () => {
    renderToStaticMarkup(createElement(PublishPage))

    const visibilityItems = selectItemRecords.filter((item) =>
      item.value === 'PUBLIC' || item.value === 'NAMESPACE_ONLY' || item.value === 'PRIVATE'
    )

    expect(visibilityItems).toEqual([
      {
        value: 'PUBLIC',
        description: 'publish.visibilityDescriptions.public',
        children: 'publish.visibilityOptions.public',
      },
      {
        value: 'NAMESPACE_ONLY',
        description: 'publish.visibilityDescriptions.namespaceOnly',
        children: 'publish.visibilityOptions.namespaceOnly',
      },
      {
        value: 'PRIVATE',
        description: 'publish.visibilityDescriptions.private',
        children: 'publish.visibilityOptions.private',
      },
    ])
  })
})
