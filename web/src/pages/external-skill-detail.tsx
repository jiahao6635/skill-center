import { useMemo, useState } from 'react'
import { useParams } from '@tanstack/react-router'
import { ExternalLink, ShieldCheck } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import { useAuth } from '@/features/auth/use-auth.ts'
import { useExternalSkillDetail, useExternalSkillFile, useExternalSkillProviders, useExternalSkillVersions, useImportExternalSkill, useValidateExternalImport } from '@/features/external-skill/use-external-skills.ts'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries.ts'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style.ts'
import { Button } from '@/shared/ui/button.tsx'
import { Card } from '@/shared/ui/card.tsx'
import { Label } from '@/shared/ui/label.tsx'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select.tsx'
import { toast } from 'sonner'

export function ExternalSkillDetailPage() {
  const { provider, slug } = useParams({ from: '/external/$provider/$slug' })
  const { isAuthenticated, user } = useAuth()
  const { data: providers, isLoading: providersLoading } = useExternalSkillProviders()
  const providerKnown = provider === 'skillhub-cn'
  const providerEnabled = providerKnown && (providers?.some((item) => item.id === provider && item.enabled) ?? false)
  const { data: detail, isLoading } = useExternalSkillDetail(slug, providerEnabled)
  const { data: versions } = useExternalSkillVersions(slug, providerEnabled)
  const { data: namespaces } = useMyNamespaces()
  const [version, setVersion] = useState('')
  const [namespace, setNamespace] = useState('')
  const [visibility, setVisibility] = useState('PRIVATE')
  const [confirmMissingLicense, setConfirmMissingLicense] = useState(false)
  const selectedVersion = version || detail?.latestVersion.version || ''
  const validateMutation = useValidateExternalImport(slug, selectedVersion)
  const importMutation = useImportExternalSkill(slug, selectedVersion)
  const validation = validateMutation.data
  const { data: skillMarkdown } = useExternalSkillFile(slug, selectedVersion, 'SKILL.md', providerEnabled)
  const namespaceOptions = useMemo(() => namespaces ?? [], [namespaces])

  const validate = async () => {
    if (!namespace || !selectedVersion) return
    try {
      setConfirmMissingLicense(false)
      await validateMutation.mutateAsync({ namespace, visibility })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Validation failed')
    }
  }

  const importSkill = async () => {
    if (!validation) return
    try {
      const result = await importMutation.mutateAsync({
        namespace, visibility, packageSha256: validation.packageSha256,
        warningDigest: validation.warningDigest, confirmWarnings: validation.warnings.length > 0,
        confirmMissingLicense: validation.licenseStatus === 'MISSING' && confirmMissingLicense,
      })
      toast.success(`Imported @${result.namespace}/${result.slug}@${result.version} (${result.status})`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Import failed')
    }
  }

  if (providersLoading) return <div className={APP_SHELL_PAGE_CLASS_NAME}>Loading...</div>
  if (!providerEnabled) {
    return <div className={APP_SHELL_PAGE_CLASS_NAME}><Card className="p-6">{providerKnown ? '部署管理员未启用 SkillHub 公共库。' : '未知的外部 Skill Provider。'}</Card></div>
  }
  if (isLoading || !detail) return <div className={APP_SHELL_PAGE_CLASS_NAME}>Loading...</div>

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="mb-2 flex items-center gap-2"><span className="rounded-md border px-2 py-1 text-xs">SkillHub 公共库</span><span className="rounded-md border px-2 py-1 text-xs">{detail.skill.category}</span></div>
          <h1 className="text-3xl font-bold">{detail.skill.displayName || detail.skill.slug}</h1>
          <p className="mt-2 text-muted-foreground">@{detail.skill.owner} · {detail.skill.slug}</p>
        </div>
        {detail.skill.sourceUrl ? <a className="inline-flex h-10 items-center rounded-md border px-4 text-sm" href={detail.skill.sourceUrl} target="_blank" rel="noopener noreferrer"><ExternalLink className="mr-2 h-4 w-4" />原站</a> : null}
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <section className="space-y-6">
          <div className="prose max-w-none dark:prose-invert"><ReactMarkdown rehypePlugins={[rehypeSanitize]}>{skillMarkdown || detail.skill.summaryZh || detail.skill.summary}</ReactMarkdown></div>
          <Card className="p-5">
            <div className="mb-2 flex items-center gap-2 font-semibold"><ShieldCheck className="h-4 w-4" />外部安全报告</div>
            <div className="space-y-2 text-sm">
              {Object.entries((detail.securityReports ?? {}) as Record<string, { statusText?: string; status?: string; reportUrl?: string }>).map(([name, report]) => (
                <div key={name} className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{name}</span>
                  <span className="text-muted-foreground">{report.statusText || report.status || '未知'}</span>
                  {report.reportUrl ? <a href={report.reportUrl} target="_blank" rel="noopener noreferrer" className="text-primary underline">查看报告</a> : null}
                </div>
              ))}
            </div>
            <p className="mt-3 text-xs text-muted-foreground">外部结论仅供参考；复制后仍执行本中心安全扫描。</p>
          </Card>
        </section>

        <Card className="h-fit space-y-4 p-5">
          <h2 className="font-semibold">复制到本中心</h2>
          <div className="space-y-2"><Label>版本</Label><Select value={selectedVersion} onValueChange={(value) => { setVersion(value); validateMutation.reset(); setConfirmMissingLicense(false) }}><SelectTrigger><SelectValue placeholder="选择版本" /></SelectTrigger><SelectContent>{(versions ?? []).map((item) => <SelectItem key={item.version} value={item.version}>{item.version}</SelectItem>)}</SelectContent></Select></div>
          <div className="space-y-2"><Label>命名空间</Label><Select value={namespace} onValueChange={(value) => { setNamespace(value); validateMutation.reset(); setConfirmMissingLicense(false) }}><SelectTrigger><SelectValue placeholder="选择命名空间" /></SelectTrigger><SelectContent>{namespaceOptions.map((item) => <SelectItem key={item.slug} value={item.slug}>@{item.slug}</SelectItem>)}</SelectContent></Select></div>
          <div className="space-y-2"><Label>可见性</Label><Select value={visibility} onValueChange={(value) => { setVisibility(value); validateMutation.reset(); setConfirmMissingLicense(false) }}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="PRIVATE">PRIVATE</SelectItem><SelectItem value="NAMESPACE_ONLY">NAMESPACE_ONLY</SelectItem><SelectItem value="PUBLIC">PUBLIC</SelectItem></SelectContent></Select></div>
          {!isAuthenticated ? <p className="text-sm text-muted-foreground">登录后可复制固定版本。</p> : null}
          <Button className="w-full" disabled={!isAuthenticated || !namespace || validateMutation.isPending} onClick={validate}>校验包</Button>
          {validation ? (
            <div className="space-y-3 text-sm">
              <p>许可证：{validation.licenseStatus}{validation.licenseExpression ? ` (${validation.licenseExpression})` : ''}</p>
              {validation.errors.map((error) => <p key={error} className="text-red-600">{error}</p>)}
              {validation.warnings.map((warning) => <p key={warning} className="text-amber-600">{warning}</p>)}
              {validation.licenseStatus === 'MISSING' && !user?.platformRoles.includes('SUPER_ADMIN') ? <p className="text-red-600">缺少许可证声明，当前账号不能复制。</p> : null}
              {validation.licenseStatus === 'MISSING' && user?.platformRoles.includes('SUPER_ADMIN') ? (
                <label className="flex items-start gap-2 rounded-md border p-3 text-sm">
                  <input type="checkbox" checked={confirmMissingLicense} onChange={(event) => setConfirmMissingLicense(event.target.checked)} />
                  <span>我确认该包缺少许可证，并明确覆盖本次 SHA-256 为 <code>{validation.packageSha256}</code> 的复制。</span>
                </label>
              ) : null}
              <Button className="w-full" disabled={!validation.valid || (validation.licenseStatus === 'MISSING' && (!user?.platformRoles.includes('SUPER_ADMIN') || !confirmMissingLicense)) || importMutation.isPending} onClick={importSkill}>确认复制</Button>
            </div>
          ) : null}
        </Card>
      </div>
    </div>
  )
}
