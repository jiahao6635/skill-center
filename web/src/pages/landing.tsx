import { useState, useMemo } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { normalizeSearchQuery } from '@/shared/lib/search-query.ts'
import { Bot, Check, Copy, Search as SearchIcon, UserRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { SkillCard } from '@/features/skill/skill-card.tsx'
import { SkeletonList } from '@/shared/components/skeleton-loader.tsx'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries.ts'
import { useInView } from '@/shared/hooks/use-in-view.ts'
import { useCopyToClipboard } from '@/shared/lib/clipboard.ts'

/* ─── Quick-start card sub-components ─── */

type QuickStartTabId = 'agent' | 'human'

interface QuickStartTab {
  id: QuickStartTabId
  labelKey: string
  icon: LucideIcon
}

const quickStartTabs: QuickStartTab[] = [
  { id: 'agent', labelKey: 'landing.quickStart.tabs.agent', icon: Bot },
  { id: 'human', labelKey: 'landing.quickStart.tabs.human', icon: UserRound },
]

function getAppBaseUrl(): string {
  if (typeof window === 'undefined') return ''
  const configuredUrl = window.__SKILLHUB_RUNTIME_CONFIG__?.appBaseUrl
  if (configuredUrl && !configuredUrl.includes('localhost')) return configuredUrl
  return `${window.location.protocol}//${window.location.host}`
}

function CompactCopyButton({ text }: { text: string }) {
  const [copied, copy] = useCopyToClipboard()
  const handleCopy = async () => {
    try { await copy(text) } catch (err) { console.error('Failed to copy:', err) }
  }
  return (
    <button
      type="button"
      onClick={handleCopy}
      aria-label={copied ? '已复制' : '复制'}
      title={copied ? '已复制' : '复制'}
      className="flex h-8 w-8 items-center justify-center rounded-full border bg-white transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 cursor-pointer flex-shrink-0"
      style={{ borderColor: 'hsl(var(--border))', color: 'hsl(var(--foreground))' }}
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
    </button>
  )
}

/* ─── Search Card (right column) ─── */

function SearchCard({ onSearch }: { onSearch?: (query: string) => void }) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<QuickStartTabId>('agent')
  const baseUrl = useMemo(() => getAppBaseUrl(), [])

  const agentPrompt = t('landing.quickStart.agent.commandTemplate', {
    defaultValue: t('landing.quickStart.agent.command'),
    url: `${baseUrl}/registry/skill-center-installer.md`,
  })
  const humanPrompt = t('landing.quickStart.human.command', { defaultValue: 'npx clawhub search <keyword>' })
  const currentPrompt = activeTab === 'agent' ? agentPrompt : humanPrompt
  const registryUrl = `${baseUrl}/registry/skill-center-installer.md`

  return (
    <div
      className="rounded-[32px] p-7 md:p-9 h-full flex flex-col"
      style={{
        background: 'linear-gradient(180deg, rgba(255,252,248,0.98) 0%, rgba(255,255,255,0.96) 100%)',
        boxShadow: '0 32px 80px -34px rgba(249,115,22,0.38)',
      }}
    >
      {/* Title */}
      <h2
        className="text-xl md:text-2xl font-semibold tracking-tight mb-2"
        style={{ color: 'hsl(var(--foreground))' }}
      >
        {t('landing.quickStart.title')}
      </h2>

      {/* Subtitle */}
      <p
        className="text-sm md:text-base max-w-md leading-relaxed mb-6"
        style={{ color: 'hsl(var(--text-secondary))' }}
      >
        {t('landing.quickStart.description', {
          defaultValue: '在同一个工作区里完成搜索和使用说明复制，减少切换步骤，直接把 Skill Center 接入你的 Agent 或 CLI。',
        })}
      </p>

      {/* Search input */}
      <div className="w-full mb-5">
        <div
          className="flex items-center bg-white rounded-[20px] border px-5 py-3.5 shadow-sm"
          style={{ borderColor: 'hsl(var(--border))' }}
        >
          <SearchIcon className="w-5 h-5 flex-shrink-0 mr-3" style={{ color: 'hsl(var(--text-placeholder))' }} strokeWidth={1.5} />
          <input
            type="text"
            placeholder={t('landing.hero.searchPlaceholder')}
            className="flex-1 bg-transparent outline-none text-base"
            style={{ color: 'hsl(var(--foreground))' }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                onSearch?.((e.target as HTMLInputElement).value)
              }
            }}
          />
        </div>
      </div>

      {/* Agent / Human toggle (segmented control, matches official) */}
      <div
        className="grid grid-cols-2 gap-2 p-1.5 rounded-[20px] mb-6"
        style={{ background: 'rgba(15,23,42,0.04)' }}
      >
        {quickStartTabs.map((tab) => {
          const isActive = tab.id === activeTab
          const Icon = tab.icon
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              aria-pressed={isActive}
              className="inline-flex items-center justify-center gap-2 rounded-[14px] px-4 py-3 text-base font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 cursor-pointer"
              style={{
                background: isActive ? '#fff' : 'transparent',
                color: isActive ? 'hsl(var(--foreground))' : 'hsl(var(--muted-foreground))',
                boxShadow: isActive ? '0 2px 8px rgba(15,23,42,0.08)' : 'none',
              }}
            >
              <Icon className="h-4 w-4" strokeWidth={1.75} />
              <span>{t(tab.labelKey)}</span>
            </button>
          )
        })}
      </div>

      {/* Prompt text area */}
      <div
        className="rounded-2xl border px-4 py-3.5 mt-auto"
        style={{
          background: 'rgba(255,255,255,0.8)',
          borderColor: 'hsl(var(--border))',
        }}
      >
        <p className="text-sm font-medium mb-2" style={{ color: 'hsl(var(--foreground))' }}>
          {activeTab === 'agent'
            ? t('landing.quickStart.agent.description', { defaultValue: '发送提示词给你的 Agent，以设置 Skill Center Registry' })
            : t('landing.quickStart.human.description', { defaultValue: '使用 CLI 工具安装 Skills' })}
        </p>
        <div className="flex items-center gap-3">
          <p className="flex-1 text-sm leading-relaxed" style={{ color: 'hsl(var(--text-secondary))' }}>
            {activeTab === 'agent' ? (
              <>
                复制提示词发送给你的 AI 助手：{' '}
                <a
                  href={registryUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-green-600 hover:text-green-700 underline transition-colors"
                >
                  {registryUrl}
                </a>
              </>
            ) : (
              <code className="font-mono text-sm" style={{ color: '#0F172A' }}>
                {humanPrompt}
              </code>
            )}
          </p>
          <CompactCopyButton text={currentPrompt} />
        </div>
      </div>
    </div>
  )
}

/* ─── Landing Page ─── */

/**
 * Marketing-style landing page for unauthenticated and first-time visitors.
 *
 * The hero area uses a two-column grid on desktop (left: copy, right: search card),
 * collapsing to a single column on mobile.
 */
export function LandingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: popularSkills, isLoading: isLoadingPopular } = useSearchSkills({
    sort: 'downloads',
    size: 6,
  })

  const { data: latestSkills, isLoading: isLoadingLatest } = useSearchSkills({
    sort: 'newest',
    size: 6,
  })

  const handleSkillClick = (namespace: string, slug: string) => {
    navigate({ to: `/space/${namespace}/${encodeURIComponent(slug)}` })
  }

  const heroView = useInView()
  const popularView = useInView()
  const latestView = useInView()

  const handleSearch = (query: string) => {
    const normalized = normalizeSearchQuery(query)
    navigate({
      to: '/search',
      search: { q: normalized, sort: 'relevance', page: 0, starredOnly: false },
    })
  }

  return (
    <>
      {/* Hero + Search Card (left-right layout) */}
      <section ref={heroView.ref} className={`relative z-10 w-full max-w-7xl mx-auto px-6 md:px-12 pt-14 md:pt-20 pb-16 md:pb-24 scroll-fade-up${heroView.inView ? ' in-view' : ''}`}>
        <div className="grid grid-cols-1 lg:grid-cols-[1.05fr_0.95fr] gap-10 lg:gap-14 items-center">
          {/* Left column — Hero copy */}
          <div className="flex flex-col items-start">
            {/* Badge */}
            <span
              className="inline-flex items-center px-4 py-2 rounded-full text-sm font-semibold mb-6"
              style={{
                background: 'rgba(249, 115, 22, 0.08)',
                color: '#C2410C',
              }}
            >
              {t('landing.hero.badge', { defaultValue: '面向团队的 AI 技能注册中心' })}
            </span>

            {/* Main title */}
            <h1
              className="text-[52px] sm:text-[64px] lg:text-[76px] font-bold tracking-[-0.04em] leading-[0.94] text-brand-gradient mb-5"
            >
              Skill Center
            </h1>

            {/* Subtitle */}
            <h2
              className="text-2xl md:text-[32px] font-semibold tracking-tight mb-4"
              style={{ color: 'hsl(var(--foreground))' }}
            >
              {t('landing.hero.title')}
            </h2>

            {/* Description */}
            <p
              className="text-base md:text-[22px] max-w-xl mb-5 leading-relaxed md:leading-8"
              style={{ color: 'hsl(var(--text-secondary))' }}
            >
              {t('landing.hero.description', {
                defaultValue: '集中搜索、安装、发布和治理团队技能包。像包管理器一样可版本化，像内部平台一样可控，让 Agent 能更快接入稳定能力。',
              })}
            </p>

            {/* Feature bullets */}
            <p
              className="text-base mb-8"
              style={{ color: 'hsl(var(--text-secondary))' }}
            >
              {t('landing.hero.featureBullet1', { defaultValue: '支持版本化发布与回滚' })}
              <span className="mx-2">•</span>
              {t('landing.hero.featureBullet2', { defaultValue: '搜索、安装、接入一条链路完成' })}
            </p>

            {/* CTA buttons */}
            <div className="flex flex-wrap gap-4">
              <Link
                to="/search"
                search={{ q: '', sort: 'relevance', page: 0, starredOnly: false }}
                className="inline-flex items-center justify-center px-8 py-3.5 rounded-2xl text-base font-medium text-white bg-brand-gradient shadow-sm hover:opacity-95 transition-opacity"
              >
                {t('landing.hero.exploreSkills')}
              </Link>
              <Link
                to="/dashboard/publish"
                className="inline-flex items-center justify-center px-8 py-3.5 rounded-2xl text-base font-medium border transition-colors"
                style={{
                  borderColor: 'hsl(var(--border))',
                  color: 'hsl(var(--foreground))',
                  background: 'transparent',
                }}
              >
                {t('landing.hero.publishSkill', { defaultValue: '发布技能' })}
              </Link>
            </div>
          </div>

          {/* Right column — Search card */}
          <SearchCard onSearch={handleSearch} />
        </div>
      </section>

      {/* Popular Downloads Section */}
      <section ref={popularView.ref} className={`relative z-10 w-full py-20 md:py-24 px-6 scroll-fade-up${popularView.inView ? ' in-view' : ''}`} style={{ background: 'var(--bg-page, hsl(var(--background)))' }}>
        <div className="max-w-6xl mx-auto space-y-6">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-3xl font-bold tracking-tight mb-2 text-center" style={{ color: 'hsl(var(--foreground))' }}>
                {t('home.popularTitle')}
              </h2>
              <p className="text-center" style={{ color: 'hsl(var(--text-secondary))' }}>{t('home.popularDescription')}</p>
            </div>
            <button
              type="button"
              className="text-sm font-medium hover:opacity-80 transition-opacity cursor-pointer"
              style={{ color: 'hsl(var(--muted-foreground))' }}
              onClick={() => navigate({ to: '/search', search: { q: '', sort: 'downloads', page: 0, starredOnly: false } })}
            >
              {t('home.viewAll')}
            </button>
          </div>
          {isLoadingPopular ? (
            <SkeletonList count={6} />
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
              {popularSkills?.items.map((skill, idx) => (
                <div key={skill.id} className={`animate-fade-up delay-${Math.min(idx + 1, 6)}`}>
                  <SkillCard
                    skill={skill}
                    onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* Latest Releases Section */}
      <section ref={latestView.ref} className={`relative z-10 w-full py-20 md:py-24 px-6 scroll-fade-up${latestView.inView ? ' in-view' : ''}`} style={{ background: 'var(--bg-page, hsl(var(--background)))' }}>
        <div className="max-w-6xl mx-auto space-y-6">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-3xl font-bold tracking-tight mb-2 text-center" style={{ color: 'hsl(var(--foreground))' }}>
                {t('home.latestTitle')}
              </h2>
              <p className="text-center" style={{ color: 'hsl(var(--text-secondary))' }}>{t('home.latestDescription')}</p>
            </div>
            <button
              type="button"
              className="text-sm font-medium hover:opacity-80 transition-opacity cursor-pointer"
              style={{ color: 'hsl(var(--muted-foreground))' }}
              onClick={() => navigate({ to: '/search', search: { q: '', sort: 'newest', page: 0, starredOnly: false } })}
            >
              {t('home.viewAll')}
            </button>
          </div>
          {isLoadingLatest ? (
            <SkeletonList count={6} />
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
              {latestSkills?.items.map((skill, idx) => (
                <div key={skill.id} className={`animate-fade-up delay-${Math.min(idx + 1, 6)}`}>
                  <SkillCard
                    skill={skill}
                    onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </section>
    </>
  )
}
