import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { MonitorSmartphone, Loader2 } from 'lucide-react'
import { getBaseUrl } from './install-command.tsx'
import { buildDeepLinkUrl, encodeConfigParam, type SkillInstallConfig } from './deep-link.ts'
import { fetchJson, getCsrfHeaders } from '@/api/client.ts'
import { useAuth } from '@/features/auth/use-auth.ts'

interface DeepLinkButtonProps {
  namespace: string
  slug: string
  version?: string
  summary?: string
}

type DownloadLinkResponse = {
  downloadUrl: string
  expiresAt: string
}

/**
 * Button that navigates to the deep link intermediate page.
 *
 * When clicked, it requests a short-lived download URL from the backend (for
 * authenticated users), then navigates to the intermediate page with that URL
 * embedded in the config. QoderWork fetches the URL directly — no auth token is
 * needed on the client side.
 */
export function DeepLinkButton({ namespace, slug, version, summary }: DeepLinkButtonProps) {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const [isLoading, setIsLoading] = useState(false)

  const baseUrl = getBaseUrl()

  const buildConfig = useCallback((downloadUrl: string): string => {
    const config: SkillInstallConfig = {
      scope: namespace === 'global' ? 'global' : 'team',
      download_url: downloadUrl,
      source: 'official',
    }

    if (version) config.version = version
    if (slug) config.skill_name = slug
    if (summary) config.description = summary

    const encoded = encodeConfigParam(config)
    return buildDeepLinkUrl(baseUrl, slug, encoded)
  }, [namespace, slug, version, summary, baseUrl])

  const fallbackDownloadUrl = `${baseUrl}/api/cli/v1/skills/${namespace}/${slug}/download`

  const handleClick = useCallback(async () => {
    if (!isAuthenticated) {
      // Not logged in — use the public CLI download endpoint (public skills only)
      window.location.href = buildConfig(fallbackDownloadUrl)
      return
    }

    setIsLoading(true)
    try {
      const params = version ? `?version=${encodeURIComponent(version)}` : ''
      const response = await fetchJson<DownloadLinkResponse>(
        `/api/web/skills/${namespace}/${slug}/download-link${params}`,
        { method: 'POST', headers: getCsrfHeaders() }
      )
      window.location.href = buildConfig(response.downloadUrl)
    } catch {
      // If issuing the link fails, fall back to the public CLI download endpoint
      window.location.href = buildConfig(fallbackDownloadUrl)
    } finally {
      setIsLoading(false)
    }
  }, [isAuthenticated, buildConfig, fallbackDownloadUrl, namespace, slug, version])

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
