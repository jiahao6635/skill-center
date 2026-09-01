import type { QueryClient } from '@tanstack/react-query'
import type { PagedResponse, SkillSummary } from '@/api/types.ts'
import { normalizeSkillDetailReturnTo } from '@/shared/lib/skill-navigation.ts'

export function isDeleteSlugConfirmationValid(expectedSlug: string, typedSlug: string) {
  return typedSlug === expectedSlug
}

export function resolveDeletedSkillReturnTo(returnTo?: string) {
  return normalizeSkillDetailReturnTo(returnTo) ?? '/search'
}

function normalizeNamespace(namespace: string) {
  return namespace.startsWith('@') ? namespace.slice(1) : namespace
}

function isDeletedSkill(skill: Pick<SkillSummary, 'id' | 'slug' | 'namespace'>, namespace: string, slug: string, skillId?: number) {
  if (skillId != null && skill.id === skillId) {
    return true
  }
  return skill.slug === slug && normalizeNamespace(skill.namespace) === normalizeNamespace(namespace)
}

function stripDeletedSkillFromListCache(data: unknown, namespace: string, slug: string, skillId?: number): unknown {
  if (Array.isArray(data)) {
    const items = data as SkillSummary[]
    const nextItems = items.filter((item) => !isDeletedSkill(item, namespace, slug, skillId))
    return nextItems.length === items.length ? data : nextItems
  }
  if (!data || typeof data !== 'object' || !('items' in data) || !Array.isArray((data as PagedResponse<SkillSummary>).items)) {
    return data
  }
  const page = data as PagedResponse<SkillSummary>
  const nextItems = page.items.filter((item) => !isDeletedSkill(item, namespace, slug, skillId))
  if (nextItems.length === page.items.length) {
    return data
  }
  return {
    ...page,
    items: nextItems,
    total: Math.max(0, page.total - (page.items.length - nextItems.length)),
  }
}

export function clearDeletedSkillQueries(queryClient: QueryClient, namespace: string, slug: string, skillId?: number) {
  const baseKey = ['skills', namespace, slug] as const
  const listQueryKeys = [
    ['skills', 'search'],
    ['skills', 'my'],
    ['skills', 'stars'],
    ['skills', 'subscriptions'],
  ] as const

  void queryClient.cancelQueries({ queryKey: baseKey })
  for (const queryKey of listQueryKeys) {
    void queryClient.cancelQueries({ queryKey: [...queryKey] })
  }
  queryClient.setQueriesData({ queryKey: baseKey }, undefined)
  queryClient.removeQueries({ queryKey: baseKey })
  if (skillId) {
    void queryClient.cancelQueries({ queryKey: ['skills', skillId, 'star'], exact: true })
    void queryClient.cancelQueries({ queryKey: ['skills', skillId, 'rating'], exact: true })
    queryClient.setQueryData(['skills', skillId, 'star'], undefined)
    queryClient.setQueryData(['skills', skillId, 'rating'], undefined)
    queryClient.removeQueries({ queryKey: ['skills', skillId, 'star'], exact: true })
    queryClient.removeQueries({ queryKey: ['skills', skillId, 'rating'], exact: true })
  }

  for (const queryKey of listQueryKeys) {
    queryClient.setQueriesData(
      { queryKey: [...queryKey] },
      (current) => stripDeletedSkillFromListCache(current, namespace, slug, skillId),
    )
    void queryClient.invalidateQueries({ queryKey: [...queryKey], refetchType: 'all' })
  }

  // The broad invalidation above may re-trigger the deleted skill's detail query before the
  // component has a chance to disable it. Remove it again to prevent a 404 refetch.
  queryClient.removeQueries({ queryKey: baseKey })
}
