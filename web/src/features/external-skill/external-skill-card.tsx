import { Download, Star } from 'lucide-react'
import type { ExternalSkillSummary } from '@/api/types.ts'
import { Card } from '@/shared/ui/card.tsx'

export function ExternalSkillCard({ skill, onClick }: { skill: ExternalSkillSummary; onClick: () => void }) {
  return (
    <Card className="h-full cursor-pointer space-y-4 p-5 transition-colors hover:border-primary/50" onClick={onClick}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-lg font-semibold">{skill.displayName || skill.slug}</h3>
          <p className="text-sm text-muted-foreground">{skill.owner ? `@${skill.owner}` : skill.slug}</p>
        </div>
        <span className="rounded-md border px-2 py-1 text-xs">SkillHub 公共库</span>
      </div>
      <p className="line-clamp-3 min-h-16 text-sm text-muted-foreground">{skill.summaryZh || skill.summary}</p>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>{skill.version}</span>
        <span className="flex gap-3"><span className="flex items-center gap-1"><Download className="h-3 w-3" />{skill.downloads}</span><span className="flex items-center gap-1"><Star className="h-3 w-3" />{skill.stars}</span></span>
      </div>
    </Card>
  )
}
