import { useMutation, useQuery } from '@tanstack/react-query'
import { fetchJson, getCsrfHeaders } from '@/api/client.ts'
import type { ExternalSkillCategory, ExternalSkillDetail, ExternalSkillImportResult, ExternalSkillImportValidation, ExternalSkillProvider, ExternalSkillSearchResponse, ExternalSkillVersion } from '@/api/types.ts'

export function useExternalSkillProviders() {
  return useQuery({
    queryKey: ['external-skills', 'providers'],
    queryFn: () => fetchJson<ExternalSkillProvider[]>('/api/web/external-skill-providers'),
    staleTime: 60_000,
  })
}

export function useExternalSkillSearch(params: { q: string; category?: string; sort: string; page: number; size: number }, enabled = true) {
  const query = new URLSearchParams({ q: params.q, sort: params.sort, page: String(params.page), size: String(params.size) })
  if (params.category) query.set('category', params.category)
  return useQuery({
    queryKey: ['external-skills', 'skillhub-cn', params],
    queryFn: () => fetchJson<ExternalSkillSearchResponse>(`/api/web/external-skills/skillhub-cn?${query}`),
    enabled,
  })
}

export function useExternalSkillCategories(enabled = true) {
  return useQuery({
    queryKey: ['external-skills', 'skillhub-cn', 'categories'],
    queryFn: () => fetchJson<ExternalSkillCategory[]>('/api/web/external-skills/skillhub-cn/categories'),
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useExternalSkillDetail(slug: string, enabled = true) {
  return useQuery({ queryKey: ['external-skills', 'skillhub-cn', slug], queryFn: () => fetchJson<ExternalSkillDetail>(`/api/web/external-skills/skillhub-cn/${encodeURIComponent(slug)}`), enabled: enabled && !!slug })
}

export function useExternalSkillVersions(slug: string, enabled = true) {
  return useQuery({ queryKey: ['external-skills', 'skillhub-cn', slug, 'versions'], queryFn: () => fetchJson<ExternalSkillVersion[]>(`/api/web/external-skills/skillhub-cn/${encodeURIComponent(slug)}/versions`), enabled: enabled && !!slug })
}

export function useExternalSkillFile(slug: string, version: string, path: string, enabled = true) {
  return useQuery({
    queryKey: ['external-skills', 'skillhub-cn', slug, version, 'file', path],
    queryFn: async () => {
      const response = await fetch(
        `/api/web/external-skills/skillhub-cn/${encodeURIComponent(slug)}/versions/${encodeURIComponent(version)}/file?path=${encodeURIComponent(path)}`,
        { credentials: 'include' },
      )
      if (!response.ok) throw new Error(`Failed to load external file (${response.status})`)
      return response.text()
    },
    enabled: enabled && Boolean(slug && version && path),
    staleTime: 10 * 60_000,
  })
}

interface ImportPayload { namespace: string; visibility: string; packageSha256?: string; warningDigest?: string; confirmWarnings?: boolean; confirmMissingLicense?: boolean }

export function useValidateExternalImport(slug: string, version: string) {
  return useMutation({ mutationFn: (payload: ImportPayload) => fetchJson<ExternalSkillImportValidation>(`/api/web/external-skills/skillhub-cn/${encodeURIComponent(slug)}/versions/${encodeURIComponent(version)}/imports/validate`, { method: 'POST', headers: getCsrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(payload), timeoutMs: 60_000 }) })
}

export function useImportExternalSkill(slug: string, version: string) {
  return useMutation({ mutationFn: (payload: ImportPayload) => fetchJson<ExternalSkillImportResult>(`/api/web/external-skills/skillhub-cn/${encodeURIComponent(slug)}/versions/${encodeURIComponent(version)}/imports`, { method: 'POST', headers: getCsrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(payload), timeoutMs: 60_000 }) })
}
