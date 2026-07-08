import { describe, expect, it, vi } from 'vitest'

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: unknown }) => children,
  useNavigate: () => vi.fn(),
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

vi.mock('lucide-react', () => {
  const StubIcon = () => null
  return {
    Bot: StubIcon,
    Check: StubIcon,
    Copy: StubIcon,
    Search: StubIcon,
    UserRound: StubIcon,
  }
})

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: () => null,
}))

vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => null,
}))

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  useSearchSkills: () => ({
    data: { items: [] },
    isLoading: false,
  }),
}))

vi.mock('@/shared/hooks/use-in-view', () => ({
  useInView: () => ({ ref: vi.fn(), inView: true }),
}))

vi.mock('@/shared/lib/search-query', () => ({
  normalizeSearchQuery: (q: string) => q.trim(),
}))

vi.mock('@/shared/lib/clipboard', () => ({
  useCopyToClipboard: () => [false, vi.fn()],
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { LandingPage } from './landing.tsx'

describe('LandingPage', () => {
  it('exports a named component function', () => {
    expect(typeof LandingPage).toBe('function')
  })

  it('renders the brand name in the hero section', () => {
    const html = renderToStaticMarkup(<LandingPage />)

    expect(html).toContain('Skill Center')
    expect(html).toContain('landing.hero.title')
  })
})
