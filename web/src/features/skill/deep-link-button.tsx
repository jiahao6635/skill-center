import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { MonitorSmartphone } from 'lucide-react'
import { getBaseUrl } from './install-command.tsx'
import { buildDeepLinkUrl, encodeConfigParam, type SkillInstallConfig } from './deep-link.ts'

interface DeepLinkButtonProps {
  namespace: string
  slug: string
  version?: string
  summary?: string
}

/**
 * Button that navigates to the deep link intermediate page.
 *
 * The intermediate page shows skill info and automatically launches
 * the QoderWork desktop app via the qoder-work:// protocol.
 */
export function DeepLinkButton({ namespace, slug, version, summary }: DeepLinkButtonProps) {
  const { t } = useTranslation()

  const deepLinkUrl = useMemo(() => {
    const baseUrl = getBaseUrl()

    const config: SkillInstallConfig = {
      scope: namespace === 'global' ? 'global' : 'team',
      download_url: `${baseUrl}/api/v1/skills/${namespace}/${slug}/download`,
      source: 'official',
    }

    if (version) config.version = version
    if (slug) config.skill_name = slug
    if (summary) config.description = summary

    const encoded = encodeConfigParam(config)
    return buildDeepLinkUrl(baseUrl, slug, encoded)
  }, [namespace, slug, version, summary])

  const handleClick = () => {
    window.location.href = deepLinkUrl
  }

  return (
    <button
      type="button"
      data-testid="deep-link-install-button"
      onClick={handleClick}
      className="relative w-full overflow-hidden rounded-xl border border-border/60 bg-muted/50 px-4 py-3 transition-colors hover:bg-muted/70 active:bg-muted/80"
    >
      <div className="flex items-center justify-center gap-2">
        <MonitorSmartphone className="h-4 w-4" />
        <span className="text-[13px] leading-relaxed text-foreground sm:text-sm">
          {t('skillDetail.deepLinkOpen')}
        </span>
      </div>
    </button>
  )
}
