import { normalizeSearchQuery } from '@/shared/lib/search-query.ts'

export const MAX_AUTHOR_NAME_LENGTH = 128

export type SearchPageSearch = {
  q: string
  namespace?: string
  author?: string
  label?: string
  sort: string
  page: number
  starredOnly: boolean
}

export function parseSearchPageSearch(search: Record<string, unknown>): SearchPageSearch {
  const author = typeof search.author === 'string'
    ? search.author.trim().slice(0, MAX_AUTHOR_NAME_LENGTH)
    : ''

  return {
    q: normalizeSearchQuery(typeof search.q === 'string' ? search.q : ''),
    namespace: typeof search.namespace === 'string' && search.namespace
      ? search.namespace.replace(/^@/, '')
      : undefined,
    author: author || undefined,
    label: typeof search.label === 'string' && search.label ? search.label : undefined,
    sort: (typeof search.sort === 'string' && search.sort) || 'newest',
    page: Number(search.page) || 0,
    starredOnly: search.starredOnly === true || search.starredOnly === 'true',
  }
}
