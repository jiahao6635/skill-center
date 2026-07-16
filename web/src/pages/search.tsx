import { startTransition, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import type { SkillSummary } from '@/api/types.ts'
import { useAuth } from '@/features/auth/use-auth.ts'
import { SearchBar } from '@/features/search/search-bar.tsx'
import { SkillCard } from '@/features/skill/skill-card.tsx'
import { SkeletonList } from '@/shared/components/skeleton-loader.tsx'
import { EmptyState } from '@/shared/components/empty-state.tsx'
import { Pagination } from '@/shared/components/pagination.tsx'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries.ts'
import { useVisibleLabels } from '@/shared/hooks/use-label-queries.ts'
import { useMyStars } from '@/shared/hooks/use-user-queries.ts'
import { formatNamespaceSearchInput, normalizeSearchQuery, parseNamespaceSearchInput } from '@/shared/lib/search-query.ts'
import { Button } from '@/shared/ui/button.tsx'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style.ts'
import { useExternalSkillCategories, useExternalSkillProviders, useExternalSkillSearch } from '@/features/external-skill/use-external-skills.ts'
import { ExternalSkillCard } from '@/features/external-skill/external-skill-card.tsx'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select.tsx'

const PAGE_SIZE = 12

function blurActiveElement() {
  if (typeof document === 'undefined' || typeof HTMLElement === 'undefined') {
    return
  }

  if (document.activeElement instanceof HTMLElement) {
    document.activeElement.blur()
  }
}

function scrollToTopOnPageChange() {
  if (typeof window === 'undefined') {
    return () => {}
  }

  let secondFrame = 0
  const firstFrame = window.requestAnimationFrame(() => {
    window.scrollTo({ top: 0, behavior: 'auto' })
    secondFrame = window.requestAnimationFrame(() => {
      window.scrollTo({ top: 0, behavior: 'auto' })
    })
  })

  return () => {
    window.cancelAnimationFrame(firstFrame)
    if (secondFrame) {
      window.cancelAnimationFrame(secondFrame)
    }
  }
}

/**
 * Skill discovery page with synchronized URL state.
 *
 * Search text, sorting, pagination, and the starred-only filter are mirrored into router search
 * params so the page can be shared, restored, and revisited without losing state.
 */
function filterStarredSkills(skills: SkillSummary[], query: string, namespace: string): SkillSummary[] {
  const normalizedQuery = query.trim().toLowerCase()
  const normalizedNamespace = namespace.trim().toLowerCase()

  return skills.filter((skill) => {
    const matchesNamespace = !normalizedNamespace || skill.namespace.toLowerCase() === normalizedNamespace
    if (!matchesNamespace) {
      return false
    }
    if (!normalizedQuery) {
      return true
    }
    return [skill.displayName, skill.summary, skill.namespace, skill.slug]
        .filter(Boolean)
        .some((value) => value!.toLowerCase().includes(normalizedQuery))
  })
}

function sortStarredSkills(skills: SkillSummary[], sort: string): SkillSummary[] {
  const sorted = [...skills]
  if (sort === 'downloads') {
    return sorted.sort((left, right) => right.downloadCount - left.downloadCount)
  }
  if (sort === 'newest' || sort === 'relevance') {
    return sorted.sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
  }
  return sorted
}

export function SearchPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const searchParams = useSearch({ from: '/search' })
  const { isAuthenticated } = useAuth()

  const q = normalizeSearchQuery(searchParams.q || '')
  const namespace = (searchParams.namespace || '').replace(/^@/, '')
  const selectedLabel = searchParams.label || ''
  const sort = searchParams.sort || 'newest'
  const page = searchParams.page ?? 0
  const starredOnly = searchParams.starredOnly ?? false
  const source = searchParams.source === 'skillhub-cn' ? 'skillhub-cn' : 'local'
  const category = searchParams.category || ''
  const isExternal = source === 'skillhub-cn'
  const [queryInput, setQueryInput] = useState(formatNamespaceSearchInput(namespace, q))
  const previousPageRef = useRef(page)

  useEffect(() => {
    setQueryInput(formatNamespaceSearchInput(namespace, q))
  }, [namespace, q])

  useEffect(() => {
    if (previousPageRef.current !== page) {
      blurActiveElement()
      const cleanupScroll = scrollToTopOnPageChange()

      previousPageRef.current = page
      return () => {
        cleanupScroll()
      }
    }

    previousPageRef.current = page
  }, [page])

  const { data, isLoading, isFetching } = useSearchSkills({
    q,
    namespace: namespace || undefined,
    label: selectedLabel || undefined,
    sort,
    page,
    size: PAGE_SIZE,
    starredOnly,
  })
  const { data: providers } = useExternalSkillProviders()
  const externalEnabled = providers?.some((provider) => provider.id === 'skillhub-cn' && provider.enabled) ?? false
  const externalQuery = useExternalSkillSearch({ q, category: category || undefined, sort, page, size: PAGE_SIZE }, isExternal && externalEnabled)
  const { data: externalCategories } = useExternalSkillCategories(isExternal && externalEnabled)
  const { data: labels } = useVisibleLabels()
  const {
    data: starredSkills,
    isLoading: isLoadingStarred,
    isFetching: isFetchingStarred,
  } = useMyStars(starredOnly && isAuthenticated)
  useEffect(() => {
    // Debounce URL updates while the user is typing so query state stays shareable without
    // triggering a navigation on every keystroke.
    const parsedInput = parseNamespaceSearchInput(queryInput)
    if (parsedInput.query === q && parsedInput.namespace === namespace) {
      return
    }

    if (!parsedInput.query && !parsedInput.namespace) {
      startTransition(() => {
        navigate({ to: '/search', search: { q: '', namespace: '', label: selectedLabel, sort, page: 0, starredOnly, source, category }, replace: page === 0 })
      })
      return
    }

    const timeoutId = window.setTimeout(() => {
      startTransition(() => {
        navigate({ to: '/search', search: { q: parsedInput.query, namespace: parsedInput.namespace, label: selectedLabel, sort, page: 0, starredOnly, source, category }, replace: true })
      })
    }, 250)

    return () => window.clearTimeout(timeoutId)
  }, [navigate, namespace, page, q, queryInput, selectedLabel, sort, starredOnly, source, category])

  const handleSearch = (query: string) => {
    const parsedInput = parseNamespaceSearchInput(query)
    setQueryInput(query)
    startTransition(() => {
      navigate({ to: '/search', search: { q: parsedInput.query, namespace: parsedInput.namespace, label: selectedLabel, sort, page: 0, starredOnly, source, category }, replace: true })
    })
  }

  const handleSortChange = (newSort: string) => {
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort: newSort, page: 0, starredOnly, source, category } })
  }

  const handlePageChange = (newPage: number) => {
    blurActiveElement()
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: newPage, starredOnly, source, category } })
  }

  const handleLabelToggle = (label: string) => {
    const nextLabel = selectedLabel === label ? '' : label
    navigate({ to: '/search', search: { q, namespace, label: nextLabel, sort, page: 0, starredOnly, source, category } })
  }

  const handleNamespaceClear = () => {
    navigate({ to: '/search', search: { q, namespace: '', label: selectedLabel, sort, page: 0, starredOnly, source, category } })
  }

  const handleStarredToggle = () => {
    if (!isAuthenticated) {
      navigate({
        to: '/login',
        search: {
          returnTo: `${window.location.pathname}${window.location.search}${window.location.hash}`,
        },
      })
      return
    }

    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: 0, starredOnly: !starredOnly, source, category } })
  }

  const handleSkillClick = (namespace: string, slug: string) => {
    navigate({ to: `/space/${namespace}/${encodeURIComponent(slug)}`, search: { returnTo: `${window.location.pathname}${window.location.search}` } })
  }

  const filteredStarredSkills = starredOnly
    ? sortStarredSkills(filterStarredSkills(starredSkills ?? [], q, namespace), sort)
    : []
  const starredPageItems = starredOnly
    ? filteredStarredSkills.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
    : []
  const totalPages = starredOnly
    ? Math.ceil(filteredStarredSkills.length / PAGE_SIZE)
    : data
      ? Math.ceil(data.total / data.size)
      : 0
  const displayItems = starredOnly ? starredPageItems : (data?.items ?? [])
  const isPageLoading = starredOnly ? isLoadingStarred : isLoading
  const isUpdatingResults = starredOnly ? isFetchingStarred && !isLoadingStarred : isFetching && !isLoading
  const resultCount = starredOnly ? filteredStarredSkills.length : (data?.total ?? 0)
  const externalItems = externalQuery.data?.items ?? []
  const activeResultCount = isExternal ? (externalQuery.data?.total ?? 0) : resultCount
  const activeTotalPages = isExternal ? Math.ceil((externalQuery.data?.total ?? 0) / PAGE_SIZE) : totalPages
  const activeLoading = isExternal ? externalQuery.isLoading : isPageLoading
  const activeUpdating = isExternal ? externalQuery.isFetching && !externalQuery.isLoading : isUpdatingResults

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      {/* Search Bar */}
      <div className="max-w-3xl mx-auto">
        <SearchBar
          value={queryInput}
          isSearching={activeUpdating}
          onChange={setQueryInput}
          onSearch={handleSearch}
        />
      </div>

      <div className="flex gap-2">
        <Button variant={!isExternal ? 'default' : 'outline'} onClick={() => navigate({ to: '/search', search: { q, sort: 'newest', page: 0, starredOnly: false, source: 'local' } })}>
          {t('search.sourceLocal')}
        </Button>
        {externalEnabled ? (
          <Button variant={isExternal ? 'default' : 'outline'} onClick={() => navigate({ to: '/search', search: { q, sort: 'relevance', page: 0, starredOnly: false, source: 'skillhub-cn' } })}>
            {t('search.sourceSkillHubCn')}
          </Button>
        ) : null}
      </div>

      {/* Sort And Filters */}
      <div className="space-y-4">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div className="flex items-center gap-3">
            <span className="text-sm font-medium text-muted-foreground">{t('search.sort.label')}</span>
            <div className="flex gap-2">
              <Button
                variant={sort === 'relevance' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('relevance')}
              >
                {t('search.sort.relevance')}
              </Button>
              <Button
                variant={sort === 'downloads' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('downloads')}
              >
                {t('search.sort.downloads')}
              </Button>
              <Button
                variant={sort === 'newest' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('newest')}
              >
                {t('search.sort.newest')}
              </Button>
            </div>
          </div>

          {activeResultCount > 0 && (
            <div className="text-sm text-muted-foreground">
              {t('search.results', { count: activeResultCount })}
            </div>
          )}
        </div>

        {activeUpdating ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('search.loadingMore')}</span>
          </div>
        ) : null}

        {!isExternal ? <div className="flex items-center gap-3">
          <span className="text-sm font-medium text-muted-foreground">{t('search.filters.label')}</span>
          <Button
            variant={starredOnly ? 'default' : 'outline'}
            size="sm"
            onClick={handleStarredToggle}
          >
            {t('search.filterStarred')}
          </Button>
          {!starredOnly && labels?.map((label) => (
            <Button
              key={label.slug}
              variant={selectedLabel === label.slug ? 'default' : 'outline'}
              size="sm"
              onClick={() => handleLabelToggle(label.slug)}
            >
              {label.displayName}
            </Button>
          ))}
          {namespace ? (
            <Button
              variant="default"
              size="sm"
              onClick={handleNamespaceClear}
            >
              {t('search.namespaceFilter', { namespace })}
            </Button>
          ) : null}
        </div> : (
          <div className="flex max-w-sm items-center gap-3">
            <span className="text-sm font-medium text-muted-foreground">Category</span>
            <Select value={category || '__all__'} onValueChange={(value) => navigate({
              to: '/search',
              search: { q, sort, page: 0, starredOnly: false, source, category: value === '__all__' ? '' : value },
            })}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">All</SelectItem>
                {(externalCategories ?? []).map((item) => (
                  <SelectItem key={item.key} value={item.key}>{item.name || item.nameEn || item.key}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
      </div>

      {/* Results */}
      {isExternal && !externalEnabled ? (
        <EmptyState title={t('search.externalDisabled')} />
      ) : activeLoading ? (
        <SkeletonList count={PAGE_SIZE} />
      ) : isExternal && externalItems.length > 0 ? (
        <>
          <div className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
            {externalItems.map((skill) => (
              <ExternalSkillCard key={skill.slug} skill={skill} onClick={() => navigate({ to: `/external/skillhub-cn/${encodeURIComponent(skill.slug)}` })} />
            ))}
          </div>
          {activeTotalPages > 1 ? <Pagination page={page} totalPages={activeTotalPages} onPageChange={handlePageChange} /> : null}
        </>
      ) : displayItems.length > 0 ? (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {displayItems.map((skill, idx) => (
              <div key={skill.id} className={`h-full animate-fade-up delay-${Math.min(idx % 6 + 1, 6)}`}>
                <SkillCard
                  skill={skill}
                  highlightStarred
                  onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                />
              </div>
            ))}
          </div>
          {activeTotalPages > 1 && (
            <Pagination
              page={page}
              totalPages={activeTotalPages}
              onPageChange={handlePageChange}
            />
          )}
        </>
      ) : (
        <EmptyState
          title={starredOnly ? t('search.noStarredResults') : t('search.noResults')}
          description={
            starredOnly
              ? (q ? t('search.noStarredResultsFor', { q }) : t('search.noStarredSkills'))
              : (q ? t('search.noResultsFor', { q }) : undefined)
          }
        />
      )}
    </div>
  )
}
