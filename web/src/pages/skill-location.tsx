import { Navigate, useParams } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchJson, WEB_API_PREFIX } from '@/api/client.ts'
import type { components } from '@/api/generated/schema'

/** A permanent share URL survives the initial move out of Private. */
export function SkillLocationPage() {
  const { skillId } = useParams({ from: '/skills/by-id/$skillId' })
  const { t } = useTranslation()
  const location = useQuery({
    queryKey: ['skill-location', skillId],
    queryFn: () => fetchJson<components['schemas']['SkillLocationResponse']>(`${WEB_API_PREFIX}/skills/by-id/${encodeURIComponent(skillId)}/location`),
    retry: false,
  })
  if (location.isPending) return <p role="status" className="p-8">{t('sharing.loading')}</p>
  if (!location.data?.namespace || !location.data.slug) return <div role="alert" className="space-y-3 p-8">
    <p>{location.error?.message || t('sharing.notFound')}</p>
    <a className="text-primary underline" href={`/login?returnTo=${encodeURIComponent(`/skills/by-id/${skillId}`)}`}>{t('sharing.signIn')}</a>
  </div>
  return <Navigate to="/space/$namespace/$slug" params={{ namespace: location.data.namespace, slug: location.data.slug }} replace />
}
