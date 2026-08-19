import { describe, expect, it } from 'vitest'
import { buildNamespaceFilterOptions } from './build-namespace-filter-options.ts'

describe('buildNamespaceFilterOptions', () => {
  it('always pins all-namespaces and global', () => {
    expect(buildNamespaceFilterOptions({ currentSlug: '', mine: [] })).toEqual({
      pinned: [
        { slug: '', group: 'all' },
        { slug: 'global', group: 'global' },
      ],
      mine: [],
      current: [],
    })
  })

  it('pins global by type or slug and drops it from mine', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: '',
      mine: [
        { slug: 'global', type: 'GLOBAL', status: 'ACTIVE' },
        { slug: 'platform', type: 'GLOBAL', status: 'ACTIVE' },
        { slug: 'acme', type: 'TEAM', status: 'ACTIVE' },
      ],
    })

    expect(result.pinned).toEqual([
      { slug: '', group: 'all' },
      { slug: 'global', group: 'global' },
    ])
    expect(result.mine).toEqual([{ slug: 'acme' }])
  })

  it('drops ARCHIVED namespaces unless they are the current slug', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: 'old-team',
      mine: [
        { slug: 'old-team', status: 'ARCHIVED' },
        { slug: 'live-team', status: 'ACTIVE' },
        { slug: 'retired', status: 'ARCHIVED' },
      ],
    })

    expect(result.mine).toEqual([{ slug: 'live-team' }])
    expect(result.current).toEqual([{ slug: 'old-team' }])
  })

  it('injects the current slug when it is missing from membership', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: '@shared-space',
      mine: [{ slug: 'acme', status: 'ACTIVE' }],
    })

    expect(result.current).toEqual([{ slug: 'shared-space' }])
  })

  it('does not inject current when it is already pinned or listed', () => {
    expect(
      buildNamespaceFilterOptions({
        currentSlug: 'global',
        mine: [{ slug: 'acme', status: 'ACTIVE' }],
      }).current,
    ).toEqual([])

    expect(
      buildNamespaceFilterOptions({
        currentSlug: 'acme',
        mine: [{ slug: 'acme', status: 'ACTIVE' }],
      }).current,
    ).toEqual([])
  })

  it('lists SUPER_ADMIN membership even when currentUserRole is null', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: '',
      mine: [{ slug: 'ops', status: 'ACTIVE', currentUserRole: null }],
    })

    expect(result.mine).toEqual([{ slug: 'ops' }])
  })

  it('keeps FROZEN namespaces in the mine list', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: '',
      mine: [
        { slug: 'paused', status: 'FROZEN' },
        { slug: 'live', status: 'ACTIVE' },
      ],
    })

    expect(result.mine).toEqual([{ slug: 'live' }, { slug: 'paused' }])
  })

  it('sorts mine namespaces by slug and dedupes', () => {
    const result = buildNamespaceFilterOptions({
      currentSlug: '',
      mine: [
        { slug: 'zeta', status: 'ACTIVE' },
        { slug: 'alpha', status: 'ACTIVE' },
        { slug: 'zeta', status: 'ACTIVE' },
      ],
    })

    expect(result.mine.map((item) => item.slug)).toEqual(['alpha', 'zeta'])
  })
})
