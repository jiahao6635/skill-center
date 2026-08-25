import { describe, expect, it } from 'vitest'
import type { SkillSummary } from '@/api/types.ts'
import { authorNameEquals, filterStarredSkills } from './filter-starred-skills.ts'

function skill(overrides: Partial<SkillSummary>): SkillSummary {
  return {
    id: 1,
    slug: 'demo',
    displayName: 'Demo',
    summary: 'summary',
    downloadCount: 0,
    starCount: 0,
    ratingCount: 0,
    namespace: 'global',
    updatedAt: '2026-03-20T00:00:00Z',
    canSubmitPromotion: false,
    ...overrides,
  }
}

describe('authorNameEquals', () => {
  it('matches ASCII names case-insensitively after trim', () => {
    expect(authorNameEquals('Alice', 'alice')).toBe(true)
    expect(authorNameEquals('  Alice  ', 'ALICE')).toBe(true)
    expect(authorNameEquals('Zhang San', 'ZhangSan')).toBe(false)
  })

  it('matches Chinese names exactly after trim', () => {
    expect(authorNameEquals('张三', '张三')).toBe(true)
    expect(authorNameEquals('张三', '张')).toBe(false)
  })
})

describe('filterStarredSkills', () => {
  it('keeps skills whose owner display name matches exactly', () => {
    const items = [
      skill({ id: 1, ownerDisplayName: 'Alice' }),
      skill({ id: 2, slug: 'other', ownerDisplayName: 'Bob' }),
    ]
    expect(filterStarredSkills(items, '', '', 'alice').map((item) => item.id)).toEqual([1])
  })

  it('drops skills when the author substring is not the full name', () => {
    const items = [skill({ ownerDisplayName: '张三丰' })]
    expect(filterStarredSkills(items, '', '', '张三')).toEqual([])
  })
})
