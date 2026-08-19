import type { KeyboardEvent, MouseEvent } from 'react'
import type { SkillSummary } from '@/api/types.ts'
import { useAuth } from '@/features/auth/use-auth.ts'
import { useStar } from '@/features/social/use-star.ts'
import { Card } from '@/shared/ui/card.tsx'
import { NamespaceBadge } from '@/shared/components/namespace-badge.tsx'
import { getHeadlineVersion } from '@/shared/lib/skill-lifecycle.ts'
import { formatCompactDateTime } from '@/shared/lib/date-time.ts'
import { formatCompactCount } from '@/shared/lib/number-format.ts'
import { Bookmark } from 'lucide-react'
import { useTranslation } from 'react-i18next'

interface SkillCardProps {
  skill: SkillSummary
  onClick?: () => void
  onNamespaceClick?: (namespace: string) => void
  highlightStarred?: boolean
}

/**
 * Reusable card for displaying one skill in lists such as landing, namespace, search, and stars.
 * When onNamespaceClick is set, the card is not a link: the title is the only detail entry and
 * the namespace badge is a sibling button so the two controls are not nested.
 */
export function SkillCard({ skill, onClick, onNamespaceClick, highlightStarred = true }: SkillCardProps) {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const { data: starStatus } = useStar(skill.id, highlightStarred && isAuthenticated)
  const showStarredHighlight = highlightStarred && isAuthenticated && starStatus?.starred
  const headlineVersion = getHeadlineVersion(skill)
  const hasNamespaceFilter = typeof onNamespaceClick === 'function'
  const isCardLink = !hasNamespaceFilter && typeof onClick === 'function'
  const namespaceType = skill.namespace === 'global' ? 'GLOBAL' : 'TEAM'
  const skillHref = `/space/${skill.namespace}/${encodeURIComponent(skill.slug)}`

  const handleNamespaceActivate = (event: MouseEvent<HTMLButtonElement> | KeyboardEvent<HTMLButtonElement>) => {
    event.preventDefault()
    event.stopPropagation()
    onNamespaceClick?.(skill.namespace)
  }

  return (
    <Card
      className="h-full p-5 cursor-pointer group relative overflow-hidden bg-white border shadow-sm transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2"
      style={{ borderColor: 'hsl(var(--border-card))' }}
      onClick={isCardLink ? onClick : undefined}
      onKeyDown={isCardLink ? (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onClick?.()
        }
      } : undefined}
      role={isCardLink ? 'link' : undefined}
      tabIndex={isCardLink ? 0 : undefined}
    >
      <div className="flex h-full flex-col">
        <div className="flex items-start justify-between mb-3">
          <div className="space-y-2">
            {hasNamespaceFilter ? (
              <a
                href={skillHref}
                className="text-inherit no-underline after:absolute after:inset-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2"
                onClick={(event) => {
                  if (
                    !onClick
                    || event.metaKey
                    || event.ctrlKey
                    || event.shiftKey
                    || event.altKey
                    || event.button !== 0
                  ) {
                    return
                  }
                  event.preventDefault()
                  onClick()
                }}
              >
                <h3 className="font-semibold text-lg group-hover:text-primary transition-colors" style={{ color: 'hsl(var(--foreground))' }}>
                  {skill.displayName}
                </h3>
              </a>
            ) : (
              <h3 className="font-semibold text-lg group-hover:text-primary transition-colors" style={{ color: 'hsl(var(--foreground))' }}>
                {skill.displayName}
              </h3>
            )}
          </div>
          <div className="flex items-center gap-2">
            {hasNamespaceFilter ? (
              <button
                type="button"
                className="relative z-10 inline-flex rounded-full border-0 bg-transparent p-0"
                aria-label={t('search.filterByNamespace', { namespace: skill.namespace })}
                onClick={handleNamespaceActivate}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter' && event.key !== ' ') {
                    return
                  }
                  handleNamespaceActivate(event)
                }}
              >
                <NamespaceBadge type={namespaceType} name={`@${skill.namespace}`} />
              </button>
            ) : (
              <NamespaceBadge type={namespaceType} name={`@${skill.namespace}`} />
            )}
          </div>
        </div>

        {skill.summary && (
          <p className="text-sm text-muted-foreground mb-4 line-clamp-2 leading-relaxed">
            {skill.summary}
          </p>
        )}

        <div className="mt-auto flex items-center gap-4 text-xs text-muted-foreground">
          {headlineVersion && (
            <span className="px-2.5 py-1 rounded-full bg-secondary/60 font-mono">
              v{headlineVersion.version}
            </span>
          )}
          <span className="flex items-center gap-1" title={t('skill.updatedAt')}>
            {t('skill.updatedAt')}: {formatCompactDateTime(skill.updatedAt)}
          </span>
          <span className="flex items-center gap-1">
            <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
            </svg>
            {formatCompactCount(skill.downloadCount)}
          </span>
          <span
            className={`flex items-center gap-1 ${showStarredHighlight ? 'font-semibold text-primary' : ''}`}
          >
            <Bookmark className={`w-3.5 h-3.5 ${showStarredHighlight ? 'fill-current' : ''}`} />
            {skill.starCount}
          </span>
          {skill.ratingAvg !== undefined && skill.ratingCount > 0 && (
            <span className="flex items-center gap-1">
              <svg className="w-3.5 h-3.5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
              </svg>
              {skill.ratingAvg.toFixed(1)}
            </span>
          )}
        </div>
      </div>
    </Card>
  )
}
