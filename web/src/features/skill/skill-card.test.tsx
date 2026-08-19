/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SkillSummary } from '@/api/types.ts'
import { SkillCard } from './skill-card.tsx'

vi.mock('@/features/auth/use-auth.ts', () => ({
  useAuth: () => ({
    isAuthenticated: false,
  }),
}))

vi.mock('@/features/social/use-star.ts', () => ({
  useStar: () => ({
    data: { starred: false },
  }),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: Record<string, unknown>) => {
        if (options && typeof options.namespace === 'string') {
          return `${key}:${options.namespace}`
        }
        return key
      },
    }),
  }
})

afterEach(() => cleanup())

const skill: SkillSummary = {
  id: 1,
  slug: 'demo',
  displayName: 'Demo Skill',
  summary: 'A reusable skill summary',
  downloadCount: 12,
  starCount: 3,
  ratingCount: 0,
  namespace: 'acme',
  updatedAt: '2026-03-20T00:00:00Z',
  canSubmitPromotion: false,
}

describe('SkillCard namespace filter branch', () => {
  it('strips whole-card link interactivity and exposes a sibling namespace button', () => {
    render(
      <SkillCard
        skill={skill}
        onClick={vi.fn()}
        onNamespaceClick={vi.fn()}
      />,
    )

    const titleLink = screen.getByRole('link', { name: 'Demo Skill' })
    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(titleLink.closest('[role="link"]')).toBeNull()
    expect(screen.getByRole('button', { name: 'search.filterByNamespace:acme' })).toBeTruthy()
  })

  it('fires onNamespaceClick from mouse, Enter, and Space without activating the title link', () => {
    const onClick = vi.fn()
    const onNamespaceClick = vi.fn()

    render(
      <SkillCard
        skill={skill}
        onClick={onClick}
        onNamespaceClick={onNamespaceClick}
      />,
    )

    const badge = screen.getByRole('button', { name: 'search.filterByNamespace:acme' })

    fireEvent.click(badge)
    fireEvent.keyDown(badge, { key: 'Enter' })
    fireEvent.keyDown(badge, { key: ' ' })

    expect(onNamespaceClick).toHaveBeenCalledTimes(3)
    expect(onNamespaceClick).toHaveBeenCalledWith('acme')
    expect(onClick).not.toHaveBeenCalled()
  })

  it('keeps the title overlay under the badge so badge clicks only filter', () => {
    const onClick = vi.fn()
    const onNamespaceClick = vi.fn()

    render(
      <SkillCard
        skill={skill}
        onClick={onClick}
        onNamespaceClick={onNamespaceClick}
      />,
    )

    const titleLink = screen.getByRole('link', { name: 'Demo Skill' })
    const badge = screen.getByRole('button', { name: 'search.filterByNamespace:acme' })

    expect(titleLink.className).toContain('after:absolute')
    expect(titleLink.className).toContain('after:inset-0')
    expect(badge.className).toContain('relative')
    expect(badge.className).toContain('z-10')

    fireEvent.click(badge)

    expect(onNamespaceClick).toHaveBeenCalledTimes(1)
    expect(onNamespaceClick).toHaveBeenCalledWith('acme')
    expect(onClick).not.toHaveBeenCalled()
  })

  it('navigates from the title link without changing namespace', () => {
    const onClick = vi.fn()
    const onNamespaceClick = vi.fn()

    render(
      <SkillCard
        skill={skill}
        onClick={onClick}
        onNamespaceClick={onNamespaceClick}
      />,
    )

    fireEvent.click(screen.getByRole('link', { name: 'Demo Skill' }))

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(onNamespaceClick).not.toHaveBeenCalled()
  })
})

describe('SkillCard whole-card link branch', () => {
  it('keeps the card as the only link and the badge as a non-button', () => {
    const onClick = vi.fn()

    render(<SkillCard skill={skill} onClick={onClick} />)

    const cardLink = screen.getByRole('link')
    expect(cardLink.textContent).toContain('Demo Skill')
    expect(cardLink.getAttribute('role')).toBe('link')
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('@acme').tagName).toBe('SPAN')

    fireEvent.click(cardLink)
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
