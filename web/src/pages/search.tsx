import { startTransition, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import type { SkillSummary } from '@/api/types.ts'
import { cn } from '@/shared/lib/utils.ts'
import { useAuth } from '@/features/auth/use-auth.ts'
import { SearchBar } from '@/features/search/search-bar.tsx'
import { SearchNamespaceFilter } from '@/features/search/search-namespace-filter.tsx'
import { SkillCard } from '@/features/skill/skill-card.tsx'
import { SkeletonList } from '@/shared/components/skeleton-loader.tsx'
import { EmptyState } from '@/shared/components/empty-state.tsx'
import { Pagination } from '@/shared/components/pagination.tsx'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries.ts'
import { useVisibleLabels } from '@/shared/hooks/use-label-queries.ts'
import { useMyStars } from '@/shared/hooks/use-user-queries.ts'
import { ApiError, handleApiError } from '@/shared/lib/api-error.ts'
import { formatNamespaceSearchInput, normalizeSearchQuery, parseNamespaceSearchInput } from '@/shared/lib/search-query.ts'
import { Button } from '@/shared/ui/button.tsx'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style.ts'

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

  const skipGlobalSearchError = Boolean(namespace) && !starredOnly
  const searchQuery = useSearchSkills(
    {
      q,
      namespace: namespace || undefined,
      label: selectedLabel || undefined,
      sort,
      page,
      size: PAGE_SIZE,
      starredOnly,
    },
    {
      skipGlobalErrorHandler: skipGlobalSearchError,
      retry: (failureCount, error) => {
        if (error instanceof ApiError && [400, 401, 403, 404].includes(error.status)) {
          return false
        }
        return failureCount < 1
      },
    },
  )
  const { data, isLoading, isFetching } = searchQuery
  const namespaceUnavailable =
    skipGlobalSearchError &&
    searchQuery.isError &&
    searchQuery.error instanceof ApiError &&
    searchQuery.error.status === 400

  useEffect(() => {
    if (!skipGlobalSearchError || !searchQuery.isError) {
      return
    }
    const error = searchQuery.error
    if (error instanceof ApiError && error.status === 400) {
      return
    }
    handleApiError(error)
  }, [searchQuery.error, searchQuery.isError, skipGlobalSearchError])
  const { data: labels } = useVisibleLabels()
  const {
    data: starredSkills,
    isLoading: isLoadingStarred,
    isFetching: isFetchingStarred,
  } = useMyStars(starredOnly && isAuthenticated)
  useEffect(() => {
    // Debounce URL updates while the user is typing so query state stays shareable without
    // triggering a navigation on every keystroke. The empty-input path must share this
    // timeout: a Select-driven namespace change leaves queryInput stale for one flush,
    // and a synchronous empty navigate would undo the slug before the format effect
    // rewrites the box to `@slug`.
    const parsedInput = parseNamespaceSearchInput(queryInput)
    if (parsedInput.query === q && parsedInput.namespace === namespace) {
      return
    }

    const isEmptyInput = !parsedInput.query && !parsedInput.namespace
    const timeoutId = window.setTimeout(() => {
      startTransition(() => {
        navigate({
          to: '/search',
          search: {
            q: parsedInput.query,
            namespace: parsedInput.namespace,
            label: selectedLabel,
            sort,
            page: 0,
            starredOnly,
          },
          replace: isEmptyInput ? page === 0 : true,
        })
      })
    }, 250)

    return () => window.clearTimeout(timeoutId)
  }, [navigate, namespace, page, q, queryInput, selectedLabel, sort, starredOnly])

  const handleSearch = (query: string) => {
    const parsedInput = parseNamespaceSearchInput(query)
    setQueryInput(query)
    startTransition(() => {
      navigate({ to: '/search', search: { q: parsedInput.query, namespace: parsedInput.namespace, label: selectedLabel, sort, page: 0, starredOnly }, replace: true })
    })
  }

  const handleSortChange = (newSort: string) => {
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort: newSort, page: 0, starredOnly } })
  }

  const handlePageChange = (newPage: number) => {
    blurActiveElement()
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: newPage, starredOnly } })
  }

  const handleLabelToggle = (label: string) => {
    const nextLabel = selectedLabel === label ? '' : label
    navigate({ to: '/search', search: { q, namespace, label: nextLabel, sort, page: 0, starredOnly } })
  }

  const handleNamespaceChange = (slug: string) => {
    navigate({
      to: '/search',
      search: {
        q,
        namespace: slug || '',
        label: selectedLabel,
        sort,
        page: 0,
        starredOnly,
      },
    })
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

    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: 0, starredOnly: !starredOnly } })
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
  const emptyTitle = namespaceUnavailable
    ? t('search.namespaceUnavailable')
    : starredOnly
      ? t('search.noStarredResults')
      : t(namespace && !q ? 'search.noResultsInNamespace' : 'search.noResults', { namespace })
  const emptyDescription = namespaceUnavailable
    ? t('search.namespaceUnavailableHint')
    : starredOnly
      ? (q ? t('search.noStarredResultsFor', { q }) : t('search.noStarredSkills'))
      : namespace
        ? (q ? t('search.noResultsForInNamespace', { q, namespace }) : t('search.noResultsInNamespaceHint'))
        : (q ? t('search.noResultsFor', { q }) : undefined)

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      {/* Search Bar */}
      <div className="max-w-3xl mx-auto">
        <SearchBar
          value={queryInput}
          isSearching={isUpdatingResults}
          onChange={setQueryInput}
          onSearch={handleSearch}
        />
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

          {resultCount > 0 && (
            <div className="text-sm text-muted-foreground">
              {t('search.results', { count: resultCount })}
            </div>
          )}
        </div>

        {isUpdatingResults ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('search.loadingMore')}</span>
          </div>
        ) : null}

        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="shrink-0 text-sm font-medium text-muted-foreground">{t('search.filters.label')}</span>
          <SearchNamespaceFilter
            value={namespace}
            onChange={handleNamespaceChange}
            isAuthenticated={isAuthenticated}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-pressed={starredOnly}
            className={cn(
              'h-8 shrink-0 rounded-lg border-border/60 bg-secondary/50 px-3 text-sm font-medium shadow-none',
              starredOnly && 'border-primary/40 bg-secondary text-foreground',
            )}
            onClick={handleStarredToggle}
          >
            {t('search.filterStarred')}
          </Button>
          {!starredOnly && labels && labels.length > 0 ? (
            <>
              <div className="mx-0.5 hidden h-4 w-px shrink-0 bg-border sm:block" aria-hidden="true" />
              <div className="min-w-0 w-full flex-1 basis-full overflow-x-auto overscroll-x-contain sm:w-auto sm:basis-0 [scrollbar-width:thin]">
                <div className="flex w-max items-center gap-2 pr-1">
                  {labels.map((label) => (
                    <Button
                      key={label.slug}
                      variant={selectedLabel === label.slug ? 'default' : 'outline'}
                      size="sm"
                      className="shrink-0"
                      onClick={() => handleLabelToggle(label.slug)}
                    >
                      {label.displayName}
                    </Button>
                  ))}
                </div>
              </div>
            </>
          ) : null}
        </div>
      </div>

      {/* Results */}
      {isPageLoading ? (
        <SkeletonList count={PAGE_SIZE} />
      ) : displayItems.length > 0 ? (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {displayItems.map((skill, idx) => (
              <div key={skill.id} className={`h-full animate-fade-up delay-${Math.min(idx % 6 + 1, 6)}`}>
                <SkillCard
                  skill={skill}
                  highlightStarred
                  onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                  onNamespaceClick={(slug) => handleNamespaceChange(slug === namespace ? '' : slug)}
                />
              </div>
            ))}
          </div>
          {totalPages > 1 && (
            <Pagination
              page={page}
              totalPages={totalPages}
              onPageChange={handlePageChange}
            />
          )}
        </>
      ) : (
        <EmptyState
          title={emptyTitle}
          description={emptyDescription}
        />
      )}
    </div>
  )
}
