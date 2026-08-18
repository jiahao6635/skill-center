import { useState, useEffect, useRef, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/shared/ui/card.tsx'
import { Button } from '@/shared/ui/button.tsx'
import { ORIGINAL_URL_SEARCH } from '@/app/router.tsx'
import {
  decodeConfigParam,
  buildProtocolUrl,
  validateSkillName,
  validateConfig,
  parseDeepLinkClient,
  deepLinkAppName,
  LAUNCH_TIMEOUT_MS,
  type DeepLinkClient,
  type SkillInstallConfig,
} from '@/features/skill/deep-link.ts'

// Parse the original URL params captured before TanStack Router rewrites
const ORIGINAL_PARAMS = new URLSearchParams(ORIGINAL_URL_SEARCH)

type PageState = 'loading' | 'error' | 'ready' | 'launching' | 'launched' | 'timeout'

export function DeepLinkInstallPage() {
  const { t, i18n } = useTranslation()
  const [pageState, setPageState] = useState<PageState>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [name, setName] = useState('')
  const [configRaw, setConfigRaw] = useState('')
  const [config, setConfig] = useState<SkillInstallConfig | null>(null)
  const [client, setClient] = useState<DeepLinkClient>('qoder-work')
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const stateRef = useRef<PageState>('loading')

  // Keep stateRef in sync so the visibilitychange handler reads the latest value
  useEffect(() => {
    stateRef.current = pageState
  }, [pageState])

  // Clean up timeout on unmount
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
        timeoutRef.current = null
      }
    }
  }, [])

  // Parse and validate URL params on mount
  useEffect(() => {
    const rawName = ORIGINAL_PARAMS.get('name')?.trim() || ''
    const rawConfig = ORIGINAL_PARAMS.get('config')?.trim() || ''
    const resolvedClient = parseDeepLinkClient(ORIGINAL_PARAMS.get('client'))
    setClient(resolvedClient)

    if (!rawName) {
      setPageState('error')
      setErrorMessage(t('deepLink.invalidName'))
      return
    }

    if (!validateSkillName(rawName)) {
      setPageState('error')
      setErrorMessage(t('deepLink.invalidName'))
      return
    }

    if (!rawConfig) {
      setPageState('error')
      setErrorMessage(t('deepLink.invalidConfig'))
      return
    }

    const decoded = decodeConfigParam(rawConfig)
    if (!decoded) {
      setPageState('error')
      setErrorMessage(t('deepLink.invalidConfig'))
      return
    }

    const missing = validateConfig(decoded)
    if (missing.length > 0) {
      setPageState('error')
      setErrorMessage(t('deepLink.missingFields', { fields: missing.join(', ') }))
      return
    }

    setName(rawName)
    setConfigRaw(rawConfig)
    setConfig(decoded)
    setPageState('ready')
  }, [t])

  // Listen for visibilitychange to detect successful launch
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden' && stateRef.current === 'launching') {
        setPageState('launched')
        if (timeoutRef.current) {
          clearTimeout(timeoutRef.current)
          timeoutRef.current = null
        }
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange)
  }, [])

  const appName = deepLinkAppName(client)

  const launchApp = useCallback(() => {
    if (!name || !configRaw) return
    if (client === 'qoder' && !config?.download_url) return

    const protocolUrl = buildProtocolUrl(name, configRaw, client, config?.download_url)
    setPageState('launching')

    // Use window.location.assign to navigate to the custom protocol URL.
    // The browser will show an "Open {app}?" dialog without actually
    // leaving the page, so the timeout detection still works.
    window.location.assign(protocolUrl)

    // Set timeout to detect if app is not installed
    timeoutRef.current = setTimeout(() => {
      if (stateRef.current === 'launching' && document.visibilityState === 'visible') {
        setPageState('timeout')
      }
    }, LAUNCH_TIMEOUT_MS)
  }, [name, configRaw, client, config?.download_url])

  // Auto-launch on ready
  useEffect(() => {
    if (pageState === 'ready') {
      // Small delay to let the UI render before auto-launching
      const autoLaunchTimer = setTimeout(() => {
        launchApp()
      }, 300)
      return () => clearTimeout(autoLaunchTimer)
    }
  }, [pageState, launchApp])

  const handleRetry = () => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
      timeoutRef.current = null
    }
    launchApp()
  }

  // Resolve display text based on user's language preference
  const isZh = i18n.language?.startsWith('zh')
  const displayName = isZh && config?.skill_name_zh ? config.skill_name_zh : (config?.skill_name ?? name)
  const displayDescription = isZh && config?.description_zh ? config.description_zh : config?.description

  /* ─── Loading state ─── */
  if (pageState === 'loading') {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4">
        <Card className="w-full max-w-md p-8 space-y-6 text-center">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-primary to-accent items-center justify-center shadow-glow mb-2 mx-auto">
            <svg className="w-8 h-8 text-primary-foreground animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold font-heading">{t('deepLink.installTitle')}</h1>
          <p className="text-muted-foreground">{t('deepLink.loading')}</p>
        </Card>
      </div>
    )
  }

  /* ─── Error state ─── */
  if (pageState === 'error') {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
        <Card className="w-full max-w-md p-8 space-y-6">
          <div className="text-center space-y-3">
            <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-red-500 to-red-600 items-center justify-center shadow-glow mb-2 mx-auto">
              <svg className="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold font-heading">{t('deepLink.errorTitle')}</h1>
            <p className="text-muted-foreground">{errorMessage}</p>
          </div>
        </Card>
      </div>
    )
  }

  /* ─── Launched state (app was successfully opened) ─── */
  if (pageState === 'launched') {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
        <Card className="w-full max-w-md p-8 space-y-6 text-center">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-emerald-500 to-emerald-600 items-center justify-center shadow-glow mb-2 mx-auto">
            <svg className="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold font-heading">{t('deepLink.launched', { app: appName })}</h1>
          <p className="text-muted-foreground">{t('deepLink.launchedDesc', { app: appName })}</p>
        </Card>
      </div>
    )
  }

  /* ─── Timeout state (app not detected) ─── */
  if (pageState === 'timeout') {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
        <Card className="w-full max-w-md p-8 space-y-6">
          <div className="text-center space-y-3">
            <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-amber-500 to-amber-600 items-center justify-center shadow-glow mb-2 mx-auto">
              <svg className="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold font-heading">{t('deepLink.timeoutTitle', { app: appName })}</h1>
            <p className="text-muted-foreground">{t('deepLink.timeoutDesc', { app: appName })}</p>
          </div>

          <div className="space-y-3">
            <Button className="w-full" onClick={handleRetry}>
              {t('deepLink.retryButton')}
            </Button>
            <p className="text-xs text-center text-muted-foreground">
              {t('deepLink.downloadHint', { app: appName })}
            </p>
          </div>
        </Card>
      </div>
    )
  }

  /* ─── Launching state ─── */
  if (pageState === 'launching') {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4">
        <Card className="w-full max-w-md p-8 space-y-6 text-center">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-primary to-accent items-center justify-center shadow-glow mb-2 mx-auto">
            <svg className="w-8 h-8 text-primary-foreground animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold font-heading">{t('deepLink.launching', { app: appName })}</h1>
          <p className="text-sm text-muted-foreground">{displayName}</p>
        </Card>
      </div>
    )
  }

  /* ─── Ready state: show skill info + launch button ─── */
  return (
    <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
      <Card className="w-full max-w-md p-8 space-y-6">
        <div className="text-center space-y-2">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-primary to-accent items-center justify-center shadow-glow mb-2 mx-auto">
            <svg className="w-8 h-8 text-primary-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold font-heading">{t('deepLink.installTitle')}</h1>
          <p className="text-sm text-muted-foreground">{t('deepLink.confirmHint')}</p>
        </div>

        {/* Skill info card */}
        <div className="rounded-xl border border-border/60 bg-muted/30 p-4 space-y-3">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center flex-shrink-0">
              <svg className="w-4 h-4 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <span className="text-sm font-semibold font-heading text-foreground truncate">{displayName}</span>
          </div>

          {displayDescription && (
            <p className="text-sm text-muted-foreground leading-relaxed">{displayDescription}</p>
          )}

          <div className="grid grid-cols-2 gap-2 text-xs">
            <div className="rounded-lg bg-background/60 p-2">
              <span className="text-muted-foreground">{t('deepLink.scopeLabel')}</span>
              <span className="ml-2 font-medium text-foreground">{config?.scope}</span>
            </div>
            {config?.version && (
              <div className="rounded-lg bg-background/60 p-2">
                <span className="text-muted-foreground">{t('deepLink.versionLabel')}</span>
                <span className="ml-2 font-medium text-foreground">{config.version}</span>
              </div>
            )}
            {config?.source && (
              <div className="rounded-lg bg-background/60 p-2">
                <span className="text-muted-foreground">{t('deepLink.sourceLabel')}</span>
                <span className="ml-2 font-medium text-foreground">{config.source}</span>
              </div>
            )}
          </div>
        </div>

        <Button className="w-full" onClick={launchApp}>
          <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
          </svg>
          {t('deepLink.launchButton', { app: appName })}
        </Button>
      </Card>
    </div>
  )
}
