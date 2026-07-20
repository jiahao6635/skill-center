import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Bot, Check, Copy, Search as SearchIcon, UserRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useCopyToClipboard } from '@/shared/lib/clipboard.ts'

type LandingQuickStartTabId = 'agent' | 'human'

interface LandingQuickStartTab {
  id: LandingQuickStartTabId
  labelKey: string
  icon: LucideIcon
}

const tabs: LandingQuickStartTab[] = [
  { id: 'agent', labelKey: 'landing.quickStart.tabs.agent', icon: Bot },
  { id: 'human', labelKey: 'landing.quickStart.tabs.human', icon: UserRound },
]

/**
 * Get the base URL for the application.
 * Prefers the runtime config if set and not localhost.
 * Falls back to the current page origin.
 */
function getAppBaseUrl(): string {
  if (typeof window === 'undefined') {
    return ''
  }
  const runtimeConfig = window.__SKILLHUB_RUNTIME_CONFIG__
  const configuredUrl = runtimeConfig?.appBaseUrl
  // Use configured URL only if it's set and not localhost
  if (configuredUrl && !configuredUrl.includes('localhost')) {
    return configuredUrl
  }
  // Fallback to current page origin
  return `${window.location.protocol}//${window.location.host}`
}

function CompactCopyButton({ text }: { text: string }) {
  const [copied, copy] = useCopyToClipboard()

  const handleCopy = async () => {
    try {
      await copy(text)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  const label = copied ? '已复制' : '复制'

  return (
    <button
      type="button"
      onClick={handleCopy}
      aria-label={label}
      title={label}
      className="flex h-8 w-8 items-center justify-center rounded-full border bg-white transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 cursor-pointer flex-shrink-0"
      style={{ borderColor: 'hsl(var(--border))', color: 'hsl(var(--foreground))' }}
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
    </button>
  )
}

interface LandingQuickStartSectionProps {
  onSearch?: (query: string) => void
}

export function LandingQuickStartSection({ onSearch }: LandingQuickStartSectionProps) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<LandingQuickStartTabId>('agent')
  const baseUrl = useMemo(() => getAppBaseUrl(), [])

  const agentPrompt = t('landing.quickStart.agent.commandTemplate', {
    defaultValue: t('landing.quickStart.agent.command'),
    url: `${baseUrl}/registry/skill-center-installer.md`,
  })

  const humanPrompt = t('landing.quickStart.human.command', {
    defaultValue: 'npx clawhub search <keyword>',
  })

  const currentPrompt = activeTab === 'agent' ? agentPrompt : humanPrompt

  const registryUrl = `${baseUrl}/registry/skill-center-installer.md`

  return (
    <section className="relative z-10 w-full px-6 py-14 md:py-20">
      <div className="max-w-4xl mx-auto">
        {/* Search card */}
        <div
          className="rounded-[32px] p-8 md:p-12"
          style={{
            background: 'linear-gradient(180deg, rgba(255,252,248,0.98) 0%, rgba(255,255,255,0.96) 100%)',
            boxShadow: '0 32px 80px -34px rgba(249,115,22,0.38)',
          }}
        >
          {/* Title */}
          <h2
            className="text-2xl md:text-[32px] font-semibold tracking-tight text-center mb-3"
            style={{ color: 'hsl(var(--foreground))' }}
          >
            {t('landing.quickStart.title')}
          </h2>

          {/* Subtitle */}
          <p
            className="text-base md:text-lg max-w-2xl mx-auto leading-relaxed text-center mb-8"
            style={{ color: 'hsl(var(--text-secondary))' }}
          >
            {t('landing.quickStart.description', {
              defaultValue: '在同一个工作区里完成搜索和使用说明复制，减少切换步骤，直接把 Skill Center 接入你的 Agent 或 CLI。',
            })}
          </p>

          {/* Search input */}
          <div className="w-full max-w-2xl mx-auto mb-6">
            <div
              className="flex items-center bg-white rounded-2xl border px-5 py-4 shadow-sm"
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

          {/* Agent / Human toggle */}
          <div className="flex justify-center gap-3 mb-8">
            {tabs.map((tab) => {
              const isActive = tab.id === activeTab
              const Icon = tab.icon
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  aria-pressed={isActive}
                  className="flex items-center gap-2 rounded-[10px] px-5 py-2.5 text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 cursor-pointer border"
                  style={{
                    background: isActive
                      ? 'linear-gradient(135deg, #6A6DFF 0%, #B85EFF 100%)'
                      : 'rgba(255,255,255,0.9)',
                    color: isActive ? '#fff' : 'hsl(var(--muted-foreground))',
                    borderColor: isActive ? 'transparent' : 'hsl(var(--border))',
                    boxShadow: isActive ? '0 4px 12px rgba(106,109,255,0.3)' : 'none',
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
            className="max-w-2xl mx-auto rounded-2xl border px-5 py-4"
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
                    阅读{' '}
                    <a
                      href={registryUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-green-600 hover:text-green-700 underline transition-colors"
                    >
                      {registryUrl}
                    </a>
                    ，并按照说明完成 Skill Center Skills Registry 配置
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
      </div>
    </section>
  )
}
