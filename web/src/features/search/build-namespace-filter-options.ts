export interface NamespaceFilterOptionInput {
  slug: string
  type?: string
  status?: string
  currentUserRole?: string | null
}

export interface NamespaceFilterOptions {
  pinned: Array<{ slug: string; group: 'all' | 'global' }>
  mine: Array<{ slug: string }>
  current: Array<{ slug: string }>
}

function isPinnedGlobal(ns: NamespaceFilterOptionInput): boolean {
  return ns.type === 'GLOBAL' || ns.slug === 'global'
}

/**
 * Builds the search namespace picker groups.
 *
 * Global is always pinned. ARCHIVED membership is omitted unless it is the
 * current URL slug (shown under "current"). FROZEN stays in "mine".
 * currentUserRole is display data only — null SUPER_ADMIN rows are kept.
 */
export function buildNamespaceFilterOptions(input: {
  currentSlug: string
  mine: NamespaceFilterOptionInput[]
}): NamespaceFilterOptions {
  const currentSlug = input.currentSlug.trim().replace(/^@/, '')
  const pinned: NamespaceFilterOptions['pinned'] = [
    { slug: '', group: 'all' },
    { slug: 'global', group: 'global' },
  ]
  const pinnedSlugs = new Set(['global'])

  const mine = input.mine
    .filter((ns) => ns.status !== 'ARCHIVED')
    .filter((ns) => !isPinnedGlobal(ns) && !pinnedSlugs.has(ns.slug))
    .slice()
    .sort((left, right) => left.slug.localeCompare(right.slug))
    .reduce<Array<{ slug: string }>>((items, ns) => {
      if (items.some((item) => item.slug === ns.slug)) {
        return items
      }
      items.push({ slug: ns.slug })
      return items
    }, [])

  const listedSlugs = new Set<string>([...pinnedSlugs, ...mine.map((item) => item.slug)])
  const current =
    currentSlug && !listedSlugs.has(currentSlug)
      ? [{ slug: currentSlug }]
      : []

  return { pinned, mine, current }
}
