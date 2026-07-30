import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { MonitorSmartphone, Loader2 } from 'lucide-react'
import { getBaseUrl } from './install-command.tsx'
import { buildDeepLinkUrl, encodeConfigParam, type SkillInstallConfig } from './deep-link.ts'
import { fetchJson } from '@/api/client.ts'
import { useAuth } from '@/features/auth/use-auth.ts'

interface DeepLinkButtonProps {
  namespace: string
  slug: string
  version?: string
  summary?: string
}

type DownloadTokenResponse = {
  token: string
  expiresAt: string
}

/**
 * Button that navigates to the deep link intermediate page.
 *
 * When clicked, it first requests a short-lived download token from the
 * backend (for authenticated users), then navigates to the intermediate page
 * with the token embedded in the config.
 */
export function DeepLinkButton({ namespace, slug, version, summary }: DeepLinkButtonProps) {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const [isLoading, setIsLoading] = useState(false)

  const baseUrl = getBaseUrl()

  const buildConfig = useCallback((authToken?: string): string => {
    const config: SkillInstallConfig = {
      scope: namespace === 'global' ? 'global' : 'team',
      download_url: `${baseUrl}/api/cli/v1/skills/${namespace}/${slug}/download`,
      source: 'official',
    }

    if (version) config.version = version
    if (slug) config.skill_name = slug
    if (summary) config.description = summary
    if (authToken) config.auth_token = authToken

    const encoded = encodeConfigParam(config)
    return buildDeepLinkUrl(baseUrl, slug, encoded)
  }, [namespace, slug, version, summary, baseUrl])

  const handleClick = useCallback(async () => {
    if (!isAuthenticated) {
      // Not logged in — navigate without token (will only work for public skills)
      window.location.href = buildConfig()
      return
    }

    setIsLoading(true)
    try {
      const response = await fetchJson<{ data: DownloadTokenResponse }>(
        '/api/web/auth/download-token',
        { method: 'POST' }
      )
      const token = response.data?.token
      window.location.href = buildConfig(token)
    } catch {
      // If token fetch fails, still navigate without token
      window.location.href = buildConfig()
    } finally {
      setIsLoading(false)
    }
  }, [isAuthenticated, buildConfig])

  return (
    <button
      type="button"
      data-testid="deep-link-install-button"
      onClick={handleClick}
      disabled={isLoading}
      className="relative w-full overflow-hidden rounded-xl border border-border/60 bg-muted/50 px-4 py-3 transition-colors hover:bg-muted/70 active:bg-muted/80 disabled:opacity-60 disabled:cursor-not-allowed"
    >
      <div className="flex items-center justify-center gap-2">
        {isLoading ? (
          <Loader2 className="h-4 w-4 animate-spin" />
        ) : (
          <MonitorSmartphone className="h-4 w-4" />
        )}
        <span className="text-[13px] leading-relaxed text-foreground sm:text-sm">
          {t('skillDetail.deepLinkOpen')}
        </span>
      </div>
    </button>
  )
}
