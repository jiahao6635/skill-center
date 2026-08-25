import { describe, expect, it } from 'vitest'
import { parseSearchPageSearch } from './parse-search-page-search.ts'

describe('parseSearchPageSearch', () => {
  it('registers author, trims it, and caps it at 128 characters', () => {
    const parsed = parseSearchPageSearch({
      q: '  agent  ',
      namespace: '@team-ai',
      author: `  ${'A'.repeat(130)}  `,
      label: 'official',
      sort: 'downloads',
      page: '2',
      starredOnly: 'true',
    })

    expect(parsed.q).toBe('agent')
    expect(parsed.namespace).toBe('team-ai')
    expect(parsed.author).toBe('A'.repeat(128))
    expect(parsed.label).toBe('official')
    expect(parsed.sort).toBe('downloads')
    expect(parsed.page).toBe(2)
    expect(parsed.starredOnly).toBe(true)
  })

  it('drops blank or non-string author values', () => {
    expect(parseSearchPageSearch({ author: '   ' }).author).toBeUndefined()
    expect(parseSearchPageSearch({ author: 12 }).author).toBeUndefined()
    expect(parseSearchPageSearch({}).author).toBeUndefined()
  })

  it('keeps author distinct from q and does not strip a leading @', () => {
    const parsed = parseSearchPageSearch({
      q: '@data-strategy',
      author: '@alice',
    })
    expect(parsed.q).toBe('@data-strategy')
    expect(parsed.author).toBe('@alice')
  })
})
