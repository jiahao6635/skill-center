import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, ensureCsrfHeaders, getCsrfHeaders, openApiClient } from '@/api/client.ts'
import type { components } from '@/api/generated/schema'

export type SharingSettings = components['schemas']['SkillSharingSettingsResponse']
export type ShareCommand = components['schemas']['SkillShareCommand']
export type ShareRequest = components['schemas']['SkillShareResponse']
export type SharePrecheck = components['schemas']['SkillSharePrecheckResponse']

export const isSharingActive = (status?: string) => status === 'SCANNING' || status === 'PENDING_REVIEW'

export function useSharingSettings(skillId: number, enabled = true) {
  return useQuery({
    queryKey: ['skill-sharing', skillId],
    queryFn: () => unwrap<SharingSettings>(openApiClient.GET('/api/web/skills/by-id/{skillId}/sharing', {
      params: { path: { skillId } }, headers: getCsrfHeaders(),
    })),
    enabled,
    refetchInterval: (query) => isSharingActive(query.state.data?.latestRequest?.status) ? 3000 : false,
  })
}

async function unwrap<T>(pending: Promise<{ data?: { code?: number; msg?: string; data?: T }; error?: unknown; response: Response }>): Promise<T> {
  try {
    const { data, error, response } = await pending
    const message = error && typeof error === 'object' && 'msg' in error && typeof error.msg === 'string' ? error.msg : data?.msg
    if (!response.ok || data?.code !== 0 || data.data == null) throw new ApiError(message || `HTTP ${response.status}`, response.status)
    return data.data
  } catch (error) {
    if (error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')) throw new ApiError('error.request.timeout', 408)
    throw error
  }
}

export function useSharePrecheck(skillId: number) {
  return useMutation({ mutationFn: async (command: ShareCommand) => unwrap<SharePrecheck>(openApiClient.POST('/api/web/skills/by-id/{skillId}/sharing/precheck', {
    params: { path: { skillId } }, body: command, headers: await ensureCsrfHeaders(), signal: AbortSignal.timeout(60_000),
  })) })
}

export function useSubmitShare(skillId: number) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: async (command: ShareCommand) => unwrap<ShareRequest>(openApiClient.POST('/api/web/skills/by-id/{skillId}/sharing', {
      params: { path: { skillId } }, body: command, headers: await ensureCsrfHeaders(), signal: AbortSignal.timeout(60_000),
    })),
    onSettled: () => queries.invalidateQueries(),
  })
}

export function useWithdrawShare(skillId: number) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: async (requestId: number) => unwrap<ShareRequest>(openApiClient.POST('/api/web/skills/by-id/{skillId}/sharing/{requestId}/withdraw', {
      params: { path: { skillId, requestId } }, headers: await ensureCsrfHeaders(), signal: AbortSignal.timeout(60_000),
    })),
    onSettled: () => queries.invalidateQueries(),
  })
}
