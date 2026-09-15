import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Share2 } from 'lucide-react'
import { Button } from '@/shared/ui/button.tsx'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/shared/ui/dialog.tsx'
import { useCopyToClipboard } from '@/shared/lib/clipboard.ts'
import {
  isSharingActive, useSharingSettings, useSharePrecheck, useSubmitShare, useWithdrawShare,
  type SharingSettings, type ShareCommand,
} from './sharing-api.ts'

interface Props { skillId: number; initialVersionId?: number; shared?: boolean }

export function SkillSharingButton({ skillId, initialVersionId, shared }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  return (
    <span onClick={(event) => event.stopPropagation()}>
      <Button size="sm" variant="outline" onClick={() => setOpen(true)}>
        <Share2 className="mr-2 h-4 w-4" />{t(shared ? 'sharing.update' : 'sharing.title')}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        {open && <SharingContent skillId={skillId} initialVersionId={initialVersionId} onClose={() => setOpen(false)} />}
      </Dialog>
    </span>
  )
}

function SharingContent({ skillId, initialVersionId, onClose }: Props & { onClose: () => void }) {
  const { t } = useTranslation()
  const id = useId()
  const ref = useRef<HTMLDivElement>(null)
  const queries = useQueryClient()
  const settings = useSharingSettings(skillId)
  const withdraw = useWithdrawShare(skillId)
  const [copied, copy] = useCopyToClipboard()
  const [error, setError] = useState('')
  const [editCompleted, setEditCompleted] = useState(!!initialVersionId)
  const request = settings.data?.latestRequest
  const active = isSharingActive(request?.status)

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])

  useEffect(() => {
    if (request?.status === 'COMPLETED') {
      void queries.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'skill-sharing' })
    }
  }, [request?.status, queries])

  return (
    <DialogContent ref={ref} tabIndex={-1} aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
      className="w-[min(calc(100vw-2rem),38rem)] p-6 sm:p-8"
      onKeyDown={(event) => {
        if (event.key === 'Escape') { event.stopPropagation(); onClose() }
        if (event.key !== 'Tab') return
        const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), [tabindex="0"]') ?? [])
        const first = items[0]
        const last = items[items.length - 1]
        if (event.shiftKey && (document.activeElement === first || document.activeElement === ref.current)) {
          event.preventDefault(); last?.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault(); first?.focus()
        }
      }}>
      <DialogTitle id={`${id}-title`}>{t('sharing.title')}</DialogTitle>
      <DialogDescription id={`${id}-description`}>{t('sharing.description')}</DialogDescription>
      {settings.isPending && <p role="status">{t('sharing.loading')}</p>}
      {settings.isError && <div role="alert"><p>{settings.error.message}</p><Button variant="outline" onClick={() => void settings.refetch()}>{t('sharing.retry')}</Button></div>}
      {request && (
        <div className="space-y-3 rounded-xl border border-border bg-muted/40 p-4" role="status" aria-live="polite">
          <p className="font-semibold">{t(`sharing.status.${request.status}`)}</p>
          <p className="text-sm text-muted-foreground">v{request.version} → {request.targetDisplayName} (@{request.targetNamespace})</p>
          <p className="text-sm">{t(`sharing.statusHelp.${request.status}`)}</p>
          {request.errorCode && <p className="text-sm text-destructive">{t(request.errorCode, { defaultValue: t('sharing.failedFallback') })}</p>}
          {request.reviewComment && <p className="text-sm">{t('sharing.reviewComment')}：{request.reviewComment}</p>}
          {active && <Button variant="outline" disabled={withdraw.isPending} onClick={async () => {
            if (!request.id) return
            setError('')
            try { await withdraw.mutateAsync(request.id) } catch (cause) { setError(cause instanceof Error ? cause.message : t('sharing.failedFallback')) }
          }}>{withdraw.isPending ? t('sharing.working') : t('sharing.withdraw')}</Button>}
          {request.status === 'COMPLETED' && <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={async () => {
              try { await copy(`${window.location.origin}/skills/by-id/${skillId}`) }
              catch { setError(t('sharing.copyFailed')) }
            }}>{t(copied ? 'sharing.copied' : 'sharing.copyLink')}</Button>
            <a className="inline-flex min-h-11 items-center px-3 text-sm text-primary underline" href={`/skills/by-id/${skillId}`}>{t('sharing.viewSkill')}</a>
            <Button variant="outline" onClick={() => setEditCompleted(true)}>{t('sharing.update')}</Button>
          </div>}
        </div>
      )}
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {settings.data && !active && (request?.status !== 'COMPLETED' || editCompleted) && <SharingForm key={`${skillId}-${request?.id ?? 'new'}-${request?.status ?? 'new'}`}
        skillId={skillId} settings={settings.data} initialVersionId={initialVersionId} onSubmitted={() => setEditCompleted(false)} />}
      {active && <p className="text-sm text-muted-foreground">{t('sharing.canClose')}</p>}
    </DialogContent>
  )
}

function SharingForm({ skillId, settings, initialVersionId, onSubmitted }: Props & { settings: SharingSettings; onSubmitted: () => void }) {
  const { t } = useTranslation()
  const id = useId()
  const versions = settings.versions ?? []
  const targets = settings.targets ?? []
  const fixedScope = settings.visibility !== 'PRIVATE'
  const [versionId, setVersionId] = useState(initialVersionId && versions.some((v) => v.id === initialVersionId) ? initialVersionId : versions[0]?.id)
  const [targetId, setTargetId] = useState(targets.length === 1 ? targets[0]?.id : undefined)
  const [scope, setScope] = useState<ShareCommand['targetVisibility']>(fixedScope && settings.visibility === 'PUBLIC' ? 'PUBLIC' : 'NAMESPACE_ONLY')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [confirmPublic, setConfirmPublic] = useState(false)
  const [confirmWarnings, setConfirmWarnings] = useState(false)
  const check = useSharePrecheck(skillId)
  const submit = useSubmitShare(skillId)
  const target = targets.find((item) => item.id === targetId)
  const busy = check.isPending || submit.isPending
  const command: ShareCommand = { versionId: versionId ?? 0, targetNamespaceId: targetId ?? 0, targetVisibility: scope, idempotencyKey: key, confirmPublic, confirmWarnings }
  const resetCheck = () => { check.reset(); submit.reset(); setKey(crypto.randomUUID()); setConfirmPublic(false); setConfirmWarnings(false) }
  const errorText = (message: string) => t(message, { defaultValue: message })
  if (!versions.length) return <div className="space-y-3">
    <p className="text-sm text-muted-foreground">{t('sharing.noVersions')}</p>
    <a className="inline-flex min-h-11 items-center text-sm text-primary underline" href={`/dashboard/publish?skillId=${skillId}&namespace=${encodeURIComponent(settings.namespace ?? '')}&visibility=PRIVATE`}>{t('sharing.savePrivate')}</a>
  </div>
  const inputClass = 'min-h-11 w-full rounded-lg border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60'
  return <form className="space-y-5" onSubmit={(event) => {
    event.preventDefault()
    if (check.data?.valid) submit.mutate(command, { onSuccess: onSubmitted })
    else check.mutate(command)
  }}>
    {settings.sharedVersion && <p className="text-sm text-muted-foreground">{t('sharing.currentShared', { version: settings.sharedVersion })}</p>}
    <div className="space-y-2">
      <label htmlFor={`${id}-version`} className="text-sm font-medium">{t('sharing.version')}</label>
      <select id={`${id}-version`} className={inputClass} value={versionId ?? ''} disabled={busy} onChange={(event) => { setVersionId(Number(event.target.value)); resetCheck() }}>
        {versions.map((version) => <option key={version.id} value={version.id}>v{version.version} · {version.fileCount} {t('sharing.files')}</option>)}
      </select>
      <p className="text-xs text-muted-foreground">{t('sharing.historyPrivate')}</p>
    </div>
    <div className="space-y-2">
      <label htmlFor={`${id}-target`} className="text-sm font-medium">{t('sharing.target')}</label>
      <select id={`${id}-target`} className={inputClass} value={targetId ?? ''} disabled={busy || fixedScope} onChange={(event) => { setTargetId(Number(event.target.value)); setScope('NAMESPACE_ONLY'); resetCheck() }} required>
        <option value="" disabled>{t('sharing.selectTarget')}</option>
        {targets.map((item) => <option key={item.id} value={item.id}>{item.displayName} (@{item.slug})</option>)}
      </select>
      {!targets.length && <p role="alert" className="text-sm text-destructive">{t('sharing.noTargets')}</p>}
    </div>
    <div className="space-y-2">
      <label htmlFor={`${id}-scope`} className="text-sm font-medium">{t('sharing.scope')}</label>
      <select id={`${id}-scope`} className={inputClass} value={scope} disabled={busy || fixedScope} onChange={(event) => { setScope(event.target.value as ShareCommand['targetVisibility']); resetCheck() }}>
        <option value="NAMESPACE_ONLY">{t(target?.type === 'GLOBAL' ? 'sharing.platformMembers' : 'sharing.teamMembers')}</option>
        {target?.type === 'GLOBAL' && <option value="PUBLIC">{t('sharing.public')}</option>}
      </select>
      <p className="text-xs text-muted-foreground">{t(fixedScope ? 'sharing.scopeFixedHelp' : 'sharing.reviewNotice')}</p>
    </div>
    {check.data && <div className="space-y-3 rounded-xl border border-border p-4" role="status">
      <p className="font-medium">{t(check.data.valid ? 'sharing.checkPassed' : 'sharing.checkFailed')}</p>
      {check.data.errors?.map((message) => <p key={message} className="text-sm text-destructive">{errorText(message)}</p>)}
      {check.data.valid && <p className="text-sm">{t('sharing.confirmSummary', { version: versions.find((v) => v.id === versionId)?.version, target: target?.displayName })}</p>}
      {check.data.warnings?.map((message) => <p key={message} className="text-sm">{errorText(message)}</p>)}
      {!!check.data.warnings?.length && <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={confirmWarnings} onChange={(event) => setConfirmWarnings(event.target.checked)} />{t('sharing.confirmWarnings')}</label>}
    </div>}
    {scope === 'PUBLIC' && <label className="flex min-h-11 items-start gap-3 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
      <input type="checkbox" className="mt-1" checked={confirmPublic} onChange={(event) => setConfirmPublic(event.target.checked)} />{t('sharing.confirmPublicText')}
    </label>}
    {(check.error || submit.error) && <p role="alert" className="text-sm text-destructive">{(check.error || submit.error)?.message}</p>}
    <Button className="min-h-11 w-full" type="submit" disabled={busy || !versionId || !targetId || (scope === 'PUBLIC' && !confirmPublic)
      || (!!check.data?.warnings?.length && !confirmWarnings) || (!!check.data && !check.data.valid)}>
      {t(busy ? 'sharing.working' : check.data?.valid ? 'sharing.submit' : 'sharing.precheck')}
    </Button>
    {check.data && !check.data.valid && <Button type="button" variant="outline" onClick={() => check.mutate(command)}>{t('sharing.retry')}</Button>}
  </form>
}
