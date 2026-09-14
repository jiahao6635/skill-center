import { useQuery } from '@tanstack/react-query'
import { fetchJson, ApiError } from '@/api/client.ts'
import type { components } from '@/api/generated/schema'

type Schema = components['schemas']
export type UsageSkillRank = Required<Omit<Schema['SkillRank'], 'downloadCount' | 'starCount'>> & {
  downloadCount?: number | null
  starCount?: number | null
}
export type UsageUserRank = Required<Schema['UserRank']>
export type UsageUserOption = Required<Schema['UserOption']>
export type UsageSummary = Required<Schema['Summary']>
type Invocation = Required<Omit<Schema['SkillInvocationItem'], 'event'>> & { event: Schema['SkillInvocationInput'] }
type Page<T> = { items: T[]; total: number; page: number; size: number }
interface Responses {
  summary: UsageSummary
  skills: Page<UsageSkillRank>
  users: Page<UsageUserRank>
  'user-options': UsageUserOption[]
  '': Page<Invocation>
}
export const SKILL_USAGE_KEY = ['admin', 'skill-usage'] as const
export type UsageParams = Record<string, string | number | undefined>

export function useSkillUsageQuery<K extends keyof Responses>(
  endpoint: K, params: UsageParams, userId: string, onDenied: () => void, enabled = true,
) {
  return useQuery({
    queryKey: [...SKILL_USAGE_KEY, userId, endpoint, params],
    enabled,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
    meta: { skipGlobalErrorHandler: true },
    queryFn: async ({ signal }): Promise<Responses[K]> => {
      const search = new URLSearchParams()
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== '') search.set(key, String(value))
      })
      try {
        return await fetchJson<Responses[K]>(`/api/v1/admin/skill-invocations${endpoint ? `/${endpoint}` : ''}?${search}`, { signal })
      } catch (error) {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) onDenied()
        throw error
      }
    },
  })
}
