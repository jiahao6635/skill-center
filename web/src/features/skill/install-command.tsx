import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Info } from 'lucide-react'
import { Button } from '@/shared/ui/button.tsx'
import { useCopyToClipboard } from '@/shared/lib/clipboard.ts'

interface InstallCommandProps {
  namespace: string
  slug: string
  version?: string
}

export function buildInstallTarget(namespace: string, slug: string): string {
  return namespace === 'global' ? slug : `${namespace}--${slug}`
}

export function getBaseUrl(): string {
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

export function buildInstallCommand(namespace: string, slug: string, baseUrl: string): string {
  const installTarget = buildInstallTarget(namespace, slug)
  return `npx clawhub install ${installTarget} --registry ${baseUrl}`
}

export function buildQoderworkPrompt(namespace: string, slug: string, baseUrl: string): string {
  const installTarget = buildInstallTarget(namespace, slug)
  return `请安装技能：npx clawhub install ${installTarget} --workdir ~/.qoderwork --registry ${baseUrl}`
}

export function buildSkillhubInstallCommand(namespace: string, slug: string, baseUrl: string): string {
  const namespaceArg = namespace === 'global' ? '' : ` --namespace ${namespace}`
  return `npx @astron-team/skillhub@latest install ${slug}${namespaceArg} --registry ${baseUrl}`
}

interface CommandBlockProps {
  command: string
}

function CommandBlock({ command }: CommandBlockProps) {
  const { t } = useTranslation()
  const [copied, copy] = useCopyToClipboard()

  const handleCopy = async () => {
    try {
      await copy(command)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  return (
    <div className="relative overflow-hidden rounded-xl border border-border/60 bg-muted/50">
      <Button
        type="button"
        variant="ghost"
        size="icon"
        onClick={handleCopy}
        title={copied ? t('copyButton.copied') : t('copyButton.copy')}
        aria-label={copied ? t('copyButton.copied') : t('copyButton.copy')}
        className="absolute right-2 top-2 z-10 h-8 w-8 rounded-md bg-background/80 backdrop-blur hover:bg-background"
      >
        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
      </Button>
      <pre className="px-4 py-3 pr-14 whitespace-pre-wrap break-all">
        <code className="font-mono text-[13px] leading-relaxed text-foreground whitespace-pre-wrap break-all sm:text-sm">
          {command}
        </code>
      </pre>
    </div>
  )
}

export function buildLoginCommand(baseUrl: string): string {
  return `npx clawhub login --registry ${baseUrl} --token <your_token>`
}

export function InstallCommand({ namespace, slug }: InstallCommandProps) {
  const { t } = useTranslation()
  const baseUrl = useMemo(() => getBaseUrl(), [])
  const loginCommand = useMemo(() => buildLoginCommand(baseUrl), [baseUrl])
  const qoderworkPrompt = useMemo(() => buildQoderworkPrompt(namespace, slug, baseUrl), [baseUrl, namespace, slug])

  return (
    <div className="space-y-4">
      {/* Login guidance - prominent info banner */}
      <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 dark:border-blue-900/50 dark:bg-blue-950/30">
        <div className="flex items-start gap-3">
          <Info className="mt-0.5 h-5 w-5 shrink-0 text-blue-600 dark:text-blue-400" />
          <div className="space-y-2">
            <p className="text-sm font-semibold text-blue-900 dark:text-blue-100">
              {t('skillDetail.installLoginTitle')}
            </p>
            <p className="text-sm text-blue-800 dark:text-blue-200">
              {t('skillDetail.installLoginHint')}
            </p>
          </div>
        </div>
        <div className="mt-3">
          <CommandBlock command={loginCommand} />
        </div>
      </div>

      {/* QoderWork install prompt */}
      <div className="space-y-2">
        <p className="text-sm font-semibold text-foreground">
          {t('skillDetail.installPromptTitle')}
        </p>
        <CommandBlock command={qoderworkPrompt} />
      </div>
    </div>
  )
}
