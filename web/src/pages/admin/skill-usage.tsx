import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Activity, Layers, RefreshCw, Search, ShieldCheck, Users, Workflow, X } from 'lucide-react'
import { useAuth } from '@/features/auth/use-auth.ts'
import { Button } from '@/shared/ui/button.tsx'
import { Card } from '@/shared/ui/card.tsx'
import { Input } from '@/shared/ui/input.tsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/ui/table.tsx'
import { SKILL_USAGE_KEY, useSkillUsageQuery, type UsageUserOption } from '@/features/admin/use-skill-usage.ts'
import { formatUsageTime, presetDates, usageRange, usageProducts, type SkillUsageSearch, type UsagePreset } from '@/features/admin/skill-usage-state.ts'
import { SkillUsageDetails, UsagePagination, UsageQueryState } from '@/features/admin/skill-usage-details.tsx'

export function SkillUsagePage() {
  const { user, hasRole, isLoading } = useAuth()
  const { t } = useTranslation()
  const client = useQueryClient()
  const [denied, setDenied] = useState(false)
  const onDenied = useCallback(() => setDenied(true), [])
  const allowed = hasRole('SUPER_ADMIN') && !denied
  useEffect(() => {
    const clear = () => {
      void client.cancelQueries({ queryKey: SKILL_USAGE_KEY })
      client.removeQueries({ queryKey: SKILL_USAGE_KEY })
    }
    if (!allowed) clear()
    return clear
  }, [allowed, client, user?.userId])
  if (isLoading) return <UsageQueryState loading retry={() => {}} />
  if (!allowed || !user) return <Card role="alert" className="p-10 text-center">{t('skillUsage.forbidden')}</Card>
  return <SkillUsageDashboard key={user.userId} userId={user.userId} onDenied={onDenied} />
}

function UserPicker({ userId, onDenied, onSelect }: { userId: string; onDenied: () => void; onSelect: (user: UsageUserOption) => void }) {
  const { t } = useTranslation()
  const [text, setText] = useState('')
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(text.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [text])
  const query = useSkillUsageQuery('user-options', { search }, userId, onDenied, open && !!search)
  const options = text.trim() === search && !query.isError ? query.data ?? [] : []
  const choose = (option: UsageUserOption) => { onSelect(option); setOpen(false); setText(''); setSearch(''); setActive(-1) }
  return <div className="relative min-w-0 flex-1" onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false) }}>
    <label htmlFor="usage-user" className="mb-2 block text-sm font-medium">{t('skillUsage.user')}</label>
    <div className="relative"><Search className="pointer-events-none absolute left-3 top-3 text-muted-foreground" size={16} aria-hidden="true" />
      <Input id="usage-user" role="combobox" autoComplete="off" aria-autocomplete="list" aria-expanded={open && !!search}
        aria-controls="usage-user-options" aria-activedescendant={active >= 0 && options[active] ? `usage-user-option-${active}` : undefined}
        className="bg-background pl-9" placeholder={t('skillUsage.userPlaceholder')} value={text}
        onFocus={() => setOpen(true)} onChange={e => { setText(e.target.value); setOpen(true); setActive(-1) }}
        onKeyDown={e => {
          if (e.key === 'Escape') setOpen(false)
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault(); setOpen(true)
            setActive(n => Math.max(0, Math.min(options.length - 1, n + (e.key === 'ArrowDown' ? 1 : -1))))
          }
          if (e.key === 'Enter' && open && active >= 0 && options[active]) { e.preventDefault(); choose(options[active]) }
        }} />
    </div>
    {open && !!search && <div className="absolute z-30 mt-2 w-full overflow-hidden rounded-xl border bg-popover text-popover-foreground shadow-lg">
      {text.trim() !== search ? <UsageQueryState loading retry={() => {}} /> : <>
        <UsageQueryState loading={query.isPending} error={query.isError} empty={!query.isPending && !options.length} retry={() => void query.refetch()} />
        <ul id="usage-user-options" role="listbox" aria-label={t('skillUsage.user')} className="max-h-72 overflow-y-auto">
          {!query.isError && options.map((option, i) => <li key={option.email}>
            <button id={`usage-user-option-${i}`} role="option" aria-selected={active === i} type="button"
              onMouseDown={e => e.preventDefault()} onClick={() => choose(option)}
              className={`w-full break-all px-4 py-3 text-left text-sm hover:bg-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring ${active === i ? 'bg-accent' : ''}`}>
              <span className="block font-medium">{option.name || t('skillUsage.unnamed')}</span><span className="text-muted-foreground">{option.email}</span>
            </button>
          </li>)}
        </ul>
      </>}
    </div>}
  </div>
}

function SkillUsageDashboard({ userId, onDenied }: { userId: string; onDenied: () => void }) {
  const { t, i18n } = useTranslation()
  const state = useSearch({ from: '/admin/skill-usage' })
  const navigate = useNavigate({ from: '/admin/skill-usage' })
  const client = useQueryClient()
  const [detail, setDetail] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const range = usageRange(state.start, state.end)
  const params = { from: range?.from, to: range?.to, email: state.email, product: state.product }
  const summary = useSkillUsageQuery('summary', params, userId, onDenied, !!range)
  const skills = useSkillUsageQuery('skills', { ...params, search: state.skill, page: state.page, size: 20 }, userId, onDenied, !!range && state.tab === 'skills')
  const users = useSkillUsageQuery('users', { ...params, page: state.page, size: 20 }, userId, onDenied, !!range && state.tab === 'users')
  const ranking = state.tab === 'skills' ? skills : users
  const number = (value: number) => new Intl.NumberFormat(i18n.language).format(value)
  const change = (patch: Partial<SkillUsageSearch>, replace = false) => {
    setDetail(null)
    void navigate({ search: previous => ({ ...previous, page: 0, ...patch }), replace })
  }
  const selectUser = (user: UsageUserOption) => change({ email: user.email, name: user.name, tab: 'skills', skill: '' })
  const refresh = async () => {
    setRefreshing(true)
    try { await client.invalidateQueries({ queryKey: [...SKILL_USAGE_KEY, userId] }) }
    finally { setRefreshing(false) }
  }
  const selectClass = 'h-10 w-full rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring'
  const cards = [
    { key: 'invocationCount', icon: Activity, label: 'calls' },
    { key: 'userCount', icon: Users, label: 'people' },
    { key: 'skillCount', icon: Layers, label: 'skillCount' },
    { key: 'sessionCount', icon: Workflow, label: 'sessions' },
  ] as const
  return <div className="space-y-6 pb-6">
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div><div className="mb-2 flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground"><ShieldCheck size={14} aria-hidden="true" />{t('skillUsage.adminOnly')}</div>
        <h1 className="text-3xl font-bold font-heading tracking-tight">{t('skillUsage.title')}</h1><p className="mt-2 text-muted-foreground">{t('skillUsage.subtitle')}</p>
      </div>
      <Button variant="outline" disabled={refreshing || !range} onClick={() => void refresh()}><RefreshCw size={16} aria-hidden="true" className={`mr-2 ${refreshing ? 'animate-spin motion-reduce:animate-none' : ''}`} />{t('skillUsage.refresh')}</Button>
    </header>

    <Card className="space-y-4 p-5">
      <div className="grid items-end gap-4 md:grid-cols-[11rem_minmax(0,1fr)_11rem_auto]">
        <div><label htmlFor="usage-preset" className="mb-2 block text-sm font-medium">{t('skillUsage.period')}</label><select id="usage-preset" className={selectClass} value={state.preset} onChange={e => {
          const preset = e.target.value as UsagePreset
          change({ preset, ...(preset === 'custom' ? {} : presetDates(preset)) })
        }}>{(['today', '7d', '30d', 'month', 'custom'] as const).map(p => <option key={p} value={p}>{t(`skillUsage.presets.${p}`)}</option>)}</select></div>
        <UserPicker userId={userId} onDenied={onDenied} onSelect={selectUser} />
        <div><label htmlFor="usage-product" className="mb-2 block text-sm font-medium">{t('skillUsage.client')}</label><select id="usage-product" className={selectClass} value={state.product} onChange={e => change({ product: e.target.value as SkillUsageSearch['product'] })}>
          {usageProducts.map(p => <option key={p} value={p}>{t(`skillUsage.products.${p || 'all'}`)}</option>)}
        </select></div>
        <Button variant="ghost" onClick={() => change({ preset: '30d', ...presetDates('30d'), email: '', name: '', product: '', skill: '', tab: 'skills' })}>{t('skillUsage.reset')}</Button>
      </div>
      <div className="flex flex-wrap items-end gap-3 text-sm">
        {state.preset === 'custom' ? <>
          <label>{t('skillUsage.start')}<Input type="date" className="mt-1 bg-background" value={state.start} onChange={e => change({ start: e.target.value })} /></label>
          <label>{t('skillUsage.end')}<Input type="date" className="mt-1 bg-background" value={state.end} onChange={e => change({ end: e.target.value })} /></label>
        </> : <span className="text-muted-foreground">{state.start} — {state.end}</span>}
        <span className="text-muted-foreground">{t('skillUsage.beijing')}</span>
        {state.email && <div className="flex min-w-0 items-center gap-2 rounded-lg bg-primary/10 px-3 py-1 text-primary"><span className="break-all">{state.name && `${state.name} · `}{state.email}</span><Button variant="ghost" className="h-9 w-9 shrink-0 p-0" aria-label={t('skillUsage.clearUser')} onClick={() => change({ email: '', name: '' })}><X size={16} aria-hidden="true" /></Button></div>}
      </div>
    </Card>

    {!range ? <Card role="alert" className="p-6 text-destructive">{t('skillUsage.invalidRange')}</Card> : <>
      {summary.isError ? <Card><UsageQueryState error retry={() => void summary.refetch()} /></Card> : <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {cards.map(({ key, icon: Icon, label }) => <Card key={key} className="p-5"><div className="flex items-center justify-between gap-2 text-sm text-muted-foreground"><span>{t(`skillUsage.${label}`)}</span><Icon size={17} aria-hidden="true" /></div>
          {summary.isPending ? <div className="mt-4 h-9 w-20 animate-pulse rounded bg-muted motion-reduce:animate-none" aria-label={t('skillUsage.loading')} /> : <div className="mt-3 text-3xl font-semibold tabular-nums tracking-tight">{number(summary.data?.[key] ?? 0)}</div>}
        </Card>)}
      </div>}

      <Card className="overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-4 border-b p-5">
          <div className="inline-flex rounded-lg bg-muted p-1" aria-label={t('skillUsage.rankingType')}>
            {(['skills', 'users'] as const).map(tab => <Button key={tab} variant={state.tab === tab ? 'default' : 'ghost'} aria-pressed={state.tab === tab} onClick={() => change({ tab })}>{t(`skillUsage.${tab}Tab`)}</Button>)}
          </div>
          {state.tab === 'skills' && <form key={state.skill} className="flex w-full gap-2 sm:w-auto" onSubmit={e => { e.preventDefault(); change({ skill: String(new FormData(e.currentTarget).get('skill') || '').trim() }) }}>
            <Input name="skill" defaultValue={state.skill} placeholder={t('skillUsage.skillSearch')} aria-label={t('skillUsage.skillSearch')} maxLength={512} className="min-w-0 bg-background sm:w-60" /><Button type="submit" variant="outline">{t('skillUsage.search')}</Button>
          </form>}
        </div>
        <div className="space-y-1 px-5 pt-5"><h2 className="text-lg font-semibold">{t(state.tab === 'skills' ? state.email ? 'skillUsage.personalSkills' : 'skillUsage.skillsTab' : 'skillUsage.usersTab')}</h2>
          <p className="text-xs leading-relaxed text-muted-foreground">{t(state.tab === 'skills' ? 'skillUsage.skillSearchHint' : 'skillUsage.usersHint')}</p>
          {state.tab === 'skills' && <p className="text-xs leading-relaxed text-muted-foreground">{t('skillUsage.registryHint')}</p>}
        </div>
        <UsageQueryState loading={ranking.isPending} error={ranking.isError} empty={ranking.data?.items.length === 0} retry={() => void ranking.refetch()} />
        {state.tab === 'skills' && !skills.isError && !!skills.data?.items.length && <>
          <div className="hidden overflow-x-auto md:block"><Table><TableHeader><TableRow>
            <TableHead>{t('skillUsage.rank')}</TableHead><TableHead>Skill</TableHead><TableHead>{t('skillUsage.calls')}</TableHead>
            {!state.email && <TableHead>{t('skillUsage.people')}</TableHead>}
            <TableHead>{t('skillUsage.downloads')}</TableHead><TableHead>{t('skillUsage.stars')}</TableHead><TableHead>{t('skillUsage.lastUsed')}</TableHead><TableHead><span className="sr-only">{t('skillUsage.actions')}</span></TableHead>
          </TableRow></TableHeader><TableBody>{skills.data.items.map(row => <TableRow key={row.skillName}>
            <TableCell className="tabular-nums text-muted-foreground">{row.rank}</TableCell><TableCell className="min-w-40 max-w-72"><span className="break-all font-medium">{row.skillName}</span>{row.unlinked && <span className="mt-1 block text-xs text-muted-foreground">{t('skillUsage.unlinked')}</span>}</TableCell>
            <TableCell className="min-w-28"><span className="font-semibold tabular-nums">{number(row.invocationCount)}</span><div className="mt-2 h-1.5 w-24 overflow-hidden rounded-full bg-muted" aria-hidden="true"><div className="h-full rounded-full bg-primary" style={{ width: `${row.peakCount ? row.invocationCount / row.peakCount * 100 : 0}%` }} /></div></TableCell>
            {!state.email && <TableCell className="tabular-nums">{number(row.userCount)}</TableCell>}
            <TableCell className="tabular-nums">{row.downloadCount == null ? '—' : number(row.downloadCount)}</TableCell><TableCell className="tabular-nums">{row.starCount == null ? '—' : number(row.starCount)}</TableCell>
            <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{formatUsageTime(row.lastUsedAt, i18n.language)}</TableCell><TableCell><Button variant="ghost" onClick={() => setDetail(row.skillName)}>{t('skillUsage.viewDetails')}</Button></TableCell>
          </TableRow>)}</TableBody></Table></div>
          <div className="divide-y px-5 md:hidden">{skills.data.items.map(row => <article key={row.skillName} className="space-y-3 py-4"><p className="break-all font-medium"><span className="mr-2 text-muted-foreground">#{row.rank}</span>{row.skillName}</p>{row.unlinked && <p className="text-xs text-muted-foreground">{t('skillUsage.unlinked')}</p>}<dl className="grid grid-cols-2 gap-2 text-sm"><dt>{t('skillUsage.calls')}</dt><dd className="text-right font-semibold">{number(row.invocationCount)}</dd>{!state.email && <><dt>{t('skillUsage.people')}</dt><dd className="text-right">{number(row.userCount)}</dd></>}<dt>{t('skillUsage.downloads')}</dt><dd className="text-right">{row.downloadCount == null ? '—' : number(row.downloadCount)}</dd><dt>{t('skillUsage.stars')}</dt><dd className="text-right">{row.starCount == null ? '—' : number(row.starCount)}</dd></dl><p className="text-xs text-muted-foreground">{t('skillUsage.lastUsed')} · {formatUsageTime(row.lastUsedAt, i18n.language)}</p><Button variant="outline" onClick={() => setDetail(row.skillName)}>{t('skillUsage.viewDetails')}</Button></article>)}</div>
        </>}
        {state.tab === 'users' && !users.isError && !!users.data?.items.length && <>
          <div className="hidden md:block"><Table><TableHeader><TableRow><TableHead>{t('skillUsage.rank')}</TableHead><TableHead>{t('skillUsage.user')}</TableHead><TableHead>{t('skillUsage.calls')}</TableHead><TableHead>{t('skillUsage.skillCount')}</TableHead><TableHead>{t('skillUsage.lastUsed')}</TableHead><TableHead><span className="sr-only">{t('skillUsage.actions')}</span></TableHead></TableRow></TableHeader><TableBody>{users.data.items.map(row => <TableRow key={row.email}>
            <TableCell>{row.rank}</TableCell><TableCell className="max-w-72 break-all"><p className="font-medium">{row.name || t('skillUsage.unnamed')}</p><p className="text-xs text-muted-foreground">{row.email}</p></TableCell><TableCell className="font-semibold tabular-nums">{number(row.invocationCount)}</TableCell><TableCell className="tabular-nums">{number(row.skillCount)}</TableCell><TableCell className="whitespace-nowrap text-xs text-muted-foreground">{formatUsageTime(row.lastUsedAt, i18n.language)}</TableCell><TableCell><Button variant="ghost" onClick={() => selectUser(row)}>{t('skillUsage.viewUser')}</Button></TableCell>
          </TableRow>)}</TableBody></Table></div>
          <div className="divide-y px-5 md:hidden">{users.data.items.map(row => <article key={row.email} className="space-y-2 py-4"><p className="font-medium">#{row.rank} · {row.name || t('skillUsage.unnamed')}</p><p className="break-all text-sm text-muted-foreground">{row.email}</p><p className="text-sm">{t('skillUsage.calls')}: {number(row.invocationCount)} · {t('skillUsage.skillCount')}: {number(row.skillCount)}</p><p className="text-xs text-muted-foreground">{t('skillUsage.lastUsed')} · {formatUsageTime(row.lastUsedAt, i18n.language)}</p><Button variant="outline" onClick={() => selectUser(row)}>{t('skillUsage.viewUser')}</Button></article>)}</div>
        </>}
        {!ranking.isError && ranking.data && <UsagePagination page={state.page} total={ranking.data.total} onChange={page => change({ page })} />}
      </Card>
    </>}
    <footer className="space-y-1 text-xs leading-relaxed text-muted-foreground"><p>{t('skillUsage.coverageHint')}</p><p>{t('skillUsage.groupingHint')}</p>{!summary.isError && summary.data && <p>{t('skillUsage.queriedAt')}: {formatUsageTime(summary.data.queriedAt, i18n.language)} · {t('skillUsage.beijing')}</p>}</footer>
    {detail && range && <SkillUsageDetails key={`${detail}:${state.email}:${range.from}:${range.to}:${state.product}`} skill={detail} params={params} userId={userId} onDenied={onDenied} onClose={() => setDetail(null)} />}
  </div>
}
