import { describe, expect, it } from 'vitest'
import { searchEmptyCopy } from './search-empty-copy.ts'

function t(key: string, options?: Record<string, unknown>) {
  if (!options) {
    return key
  }
  return `${key}:${JSON.stringify(options)}`
}

describe('searchEmptyCopy', () => {
  it('uses namespaceUnavailable before any author copy', () => {
    const copy = searchEmptyCopy({
      namespaceUnavailable: true,
      starredOnly: true,
      author: '张三',
      namespace: 'team-ai',
      q: 'agent',
      t,
    })
    expect(copy.title).toBe('search.namespaceUnavailable')
    expect(copy.description).toBe('search.namespaceUnavailableHint')
  })

  it('uses starred author copy including the exact-name hint', () => {
    const copy = searchEmptyCopy({
      namespaceUnavailable: false,
      starredOnly: true,
      author: '张三',
      namespace: 'team-ai',
      q: 'agent',
      t,
    })
    expect(copy.title).toContain('search.noStarredResultsForQueryAndAuthor')
    expect(copy.description).toBe('search.noResultsForAuthorHint')
  })

  it('uses author+namespace+q before author-only copy', () => {
    const copy = searchEmptyCopy({
      namespaceUnavailable: false,
      starredOnly: false,
      author: '张三',
      namespace: 'team-ai',
      q: 'agent',
      t,
    })
    expect(copy.title).toContain('search.noResultsForQueryAuthorInNamespace')
    expect(copy.description).toBe('search.noResultsForAuthorHint')
  })
})
