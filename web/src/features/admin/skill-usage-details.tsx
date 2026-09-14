import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Copy, X } from 'lucide-react'
import { Button } from '@/shared/ui/button.tsx'
import { useSkillUsageQuery, type UsageParams } from './use-skill-usage.ts'
import { formatUsageTime } from './skill-usage-state.ts'

export function UsagePagination({ page, total, onChange }: { page: number; total: number; onChange: (page: number) => void }) {
  const { t } = useTranslation()
  return <div className="flex flex-wrap items-center justify-end gap-3 border-t p-4 text-sm">
    <span className="text-muted-foreground">{t('skillUsage.pagination', { page: page + 1, total })}</span>
    <Button variant="outline" disabled={page === 0} onClick={() => onChange(page - 1)}>{t('skillUsage.previous')}</Button>
    <Button variant="outline" disabled={(page + 1) * 20 >= total} onClick={() => onChange(page + 1)}>{t('skillUsage.next')}</Button>
  </div>
}

export function UsageQueryState({ loading, error, empty, retry }: { loading?: boolean; error?: boolean; empty?: boolean; retry: () => void }) {
  const { t } = useTranslation()
  if (error) return <div role="alert" className="space-y-3 p-8 text-center"><p>{t('skillUsage.error')}</p><Button variant="outline" onClick={retry}>{t('skillUsage.retry')}</Button></div>
  if (loading) return <div role="status" aria-label={t('skillUsage.loading')} className="space-y-3 p-5">{[1, 2, 3].map(n => <div key={n} className="h-12 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />)}</div>
  if (empty) return <p className="p-10 text-center text-muted-foreground">{t('skillUsage.empty')}</p>
  return null
}

/** Native modal dialog supplies focus containment, Escape and background inertness. */
export function SkillUsageDetails({ skill, params, userId, onDenied, onClose }: {
  skill: string; params: UsageParams; userId: string; onDenied: () => void; onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const dialog = useRef<HTMLDialogElement>(null)
  const [page, setPage] = useState(0)
  const [copyStatus, setCopyStatus] = useState('')
  const query = useSkillUsageQuery('', { ...params, skillName: skill, page, size: 20 }, userId, onDenied)
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const element = dialog.current
    element?.showModal()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      element?.close()
      document.body.style.overflow = previousOverflow
      opener?.focus()
    }
  }, [])
  const copy = async (value: string) => {
    try { await navigator.clipboard.writeText(value); setCopyStatus(t('skillUsage.copied')) }
    catch { setCopyStatus(t('skillUsage.copyFailed')) }
  }
  return <dialog ref={dialog} aria-labelledby="usage-detail-title" onCancel={onClose}
    className="fixed inset-y-0 left-auto right-0 m-0 h-dvh max-h-none w-full max-w-none border-l bg-background p-0 text-foreground shadow-xl backdrop:bg-black/50 md:w-[38rem]">
    <div className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b bg-background p-6">
      <div className="min-w-0"><h2 id="usage-detail-title" className="text-xl font-semibold">{t('skillUsage.details')}</h2><p className="mt-1 break-all text-sm text-muted-foreground">{skill}</p><p className="mt-2 text-xs text-muted-foreground">{t('skillUsage.detailScope')}</p></div>
      <Button variant="ghost" className="h-11 w-11 shrink-0 p-0" aria-label={t('skillUsage.close')} onClick={onClose}><X aria-hidden="true" size={20} /></Button>
    </div>
    <UsageQueryState loading={query.isPending} error={query.isError} empty={query.data?.items.length === 0} retry={() => void query.refetch()} />
    {!query.isError && query.data && <>
      <ol className="divide-y px-6">{query.data.items.map(item => {
        const e = item.event
        return <li key={item.id} className="space-y-3 py-5">
          <div className="flex flex-wrap justify-between gap-2"><time className="text-sm font-medium">{formatUsageTime(e.occurred_at, i18n.language)}</time><span className="rounded bg-muted px-2 py-1 text-xs">{e.trigger_mode === 'manual' ? t('skillUsage.manual') : t('skillUsage.automatic')}</span></div>
          <p className="break-all text-sm">{e.name && <span className="mr-2 font-medium">{e.name}</span>}{e.email}</p>
          <dl className="grid grid-cols-[5rem_minmax(0,1fr)] gap-2 text-sm">
            <dt className="text-muted-foreground">Skill</dt><dd className="break-all">{e.skill_name}</dd>
            <dt className="text-muted-foreground">{t('skillUsage.client')}</dt><dd>{t(`skillUsage.products.${e.client_product || 'unknown'}`)}</dd>
            <dt className="text-muted-foreground">{t('skillUsage.session')}</dt><dd className="flex items-start gap-2"><code className="min-w-0 break-all text-xs">{e.session_id}</code><Button variant="ghost" className="h-9 w-9 shrink-0 p-0" aria-label={t('skillUsage.copySession')} onClick={() => void copy(e.session_id || '')}><Copy size={14} aria-hidden="true" /></Button></dd>
          </dl>
        </li>
      })}</ol>
      <UsagePagination page={page} total={query.data.total} onChange={setPage} />
    </>}
    <p role="status" className="px-6 pb-4 text-sm text-muted-foreground">{copyStatus}</p>
  </dialog>
}
