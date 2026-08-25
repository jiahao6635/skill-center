import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { UploadZone } from '@/features/publish/upload-zone.tsx'
import {
  extractPrecheckWarnings,
  isFrontmatterFailureMessage,
  isPackageTooLargeError,
  isPrecheckConfirmationMessage,
  isPrecheckFailureMessage,
  isVersionExistsMessage,
} from '@/features/publish/publish-error-utils.ts'
import { isPackageOverSizeLimit, MAX_PACKAGE_BYTES } from '@/features/publish/package-limits.ts'
import { normalizePublishPrefill } from '@/features/publish/publish-prefill.ts'
import { formatFileSize } from '@/shared/lib/file-size.ts'
import { Button } from '@/shared/ui/button.tsx'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select.tsx'
import { Label } from '@/shared/ui/label.tsx'
import { Card } from '@/shared/ui/card.tsx'
import { usePublishSkill } from '@/shared/hooks/use-skill-queries.ts'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries.ts'
import { ConfirmDialog } from '@/shared/components/confirm-dialog.tsx'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header.tsx'
import { toast } from '@/shared/lib/toast.ts'
import { ApiError } from '@/api/client.ts'

const EMPTY_NAMESPACE_VALUE = '__select_namespace__'
const PRIVATE_NAMESPACE_SLUG = 'private'

type FileErrorState = {
  title: string
  description: string
}

export function PublishPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const search = useSearch({ from: '/dashboard/publish' })
  const prefill = normalizePublishPrefill(search)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<FileErrorState | null>(null)
  const [uploadZoneNonce, setUploadZoneNonce] = useState(0)
  const [namespaceSlug, setNamespaceSlug] = useState<string>(
    prefill.visibility === 'PRIVATE' ? PRIVATE_NAMESPACE_SLUG : prefill.namespace
  )
  const [visibility, setVisibility] = useState<string>(prefill.visibility)
  const [warningDialogOpen, setWarningDialogOpen] = useState(false)
  const [precheckWarnings, setPrecheckWarnings] = useState<string[]>([])
  const previousNamespaceRef = useRef<string>(prefill.namespace)

  const { data: namespaces, isLoading: isLoadingNamespaces } = useMyNamespaces()
  const publishMutation = usePublishSkill()
  const isPrivate = visibility === 'PRIVATE'
  const selectedNamespace = namespaces?.find((ns) => ns.slug === namespaceSlug)
  const namespaceOnlyLabel = selectedNamespace?.type === 'GLOBAL'
    ? t('publish.visibilityOptions.loggedInUsersOnly')
    : t('publish.visibilityOptions.namespaceOnly')

  useEffect(() => {
    const resolvedNamespace = prefill.visibility === 'PRIVATE'
      ? PRIVATE_NAMESPACE_SLUG
      : prefill.namespace
    setNamespaceSlug(resolvedNamespace)
    setVisibility(prefill.visibility)
    if (prefill.visibility !== 'PRIVATE') {
      previousNamespaceRef.current = prefill.namespace
    }
  }, [prefill.namespace, prefill.visibility])

  const handleVisibilityChange = useCallback((value: string) => {
    setVisibility(value)
    if (value === 'PRIVATE') {
      previousNamespaceRef.current = namespaceSlug
      setNamespaceSlug(PRIVATE_NAMESPACE_SLUG)
    } else if (namespaceSlug === PRIVATE_NAMESPACE_SLUG) {
      setNamespaceSlug(previousNamespaceRef.current)
    }
  }, [namespaceSlug])

  const resetUploadZone = () => {
    setUploadZoneNonce((value) => value + 1)
  }

  const handleRemoveSelectedFile = () => {
    setSelectedFile(null)
    setFileError(null)
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
    resetUploadZone()
  }

  const showPackageTooLarge = (file?: File) => {
    const title = t('publish.packageTooLargeTitle')
    const description = t('publish.packageTooLargeDescription', {
      name: file?.name ?? t('publish.packageTooLargeUnknownSize'),
      size: file ? formatFileSize(file.size) : t('publish.packageTooLargeUnknownSize'),
      limit: formatFileSize(MAX_PACKAGE_BYTES),
    })
    setSelectedFile(null)
    setFileError({ title, description })
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
    resetUploadZone()
    toast.error(title, description)
  }

  const handleFileSelect = (file: File) => {
    if (isPackageOverSizeLimit(file.size)) {
      showPackageTooLarge(file)
      return
    }
    setSelectedFile(file)
    setFileError(null)
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
  }

  const handleFileRejected = (reason: 'too-large' | 'invalid-type', file?: File) => {
    if (reason === 'too-large' || (file != null && isPackageOverSizeLimit(file.size))) {
      showPackageTooLarge(file)
      return
    }
    const title = t('publish.invalidFileType')
    setSelectedFile(null)
    setFileError({ title, description: t('upload.formatHint') })
    resetUploadZone()
    toast.error(title)
  }

  const publishSkill = async (confirmWarnings = false) => {
    if (!selectedFile || !namespaceSlug) {
      toast.error(t('publish.selectRequired'))
      return
    }
    if (isPackageOverSizeLimit(selectedFile.size)) {
      showPackageTooLarge(selectedFile)
      return
    }

    try {
      const result = await publishMutation.mutateAsync({
        namespace: namespaceSlug,
        file: selectedFile,
        visibility,
        confirmWarnings,
      })
      setPrecheckWarnings([])
      setWarningDialogOpen(false)
      const skillLabel = `${result.namespace}/${result.slug}@${result.version}`
      if (result.status === 'PUBLISHED') {
        toast.success(
          t('publish.publishedTitle'),
          t('publish.publishedDescription', { skill: skillLabel })
        )
      } else {
        toast.success(
          t('publish.pendingReviewTitle'),
          t('publish.pendingReviewDescription', { skill: skillLabel })
        )
      }
      navigate({ to: '/dashboard/skills' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 408) {
        toast.error(t('publish.timeoutTitle'), t('publish.timeoutDescription'))
        return
      }

      if (error instanceof ApiError && isVersionExistsMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.versionExistsTitle'),
          t('publish.versionExistsDescription'),
        )
        return
      }

      if (error instanceof ApiError && isPrecheckConfirmationMessage(error.serverMessage || error.message)) {
        setPrecheckWarnings(extractPrecheckWarnings(error.serverMessage || error.message))
        setWarningDialogOpen(true)
        return
      }

      if (error instanceof ApiError && isPrecheckFailureMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.precheckFailedTitle'),
          error.serverMessage || t('publish.precheckFailedDescription'),
        )
        return
      }

      if (error instanceof ApiError && isFrontmatterFailureMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.frontmatterFailedTitle'),
          error.serverMessage || t('publish.frontmatterFailedDescription'),
        )
        return
      }

      if (error instanceof ApiError && isPackageTooLargeError(error.status, error.serverMessage || error.message)) {
        const title = t('publish.packageTooLargeTitle')
        const description = t('publish.packageTooLargeServerDescription', {
          limit: formatFileSize(MAX_PACKAGE_BYTES),
        })
        setFileError({ title, description })
        toast.error(title, description)
        return
      }

      toast.error(t('publish.error'), error instanceof Error ? error.message : '')
    }
  }

  const handlePublish = async () => {
    await publishSkill(false)
  }

  return (
    <div className="max-w-2xl mx-auto space-y-8 animate-fade-up">
      <DashboardPageHeader title={t('publish.title')} subtitle={t('publish.subtitle')} />

      <Card className="p-4 bg-blue-500/5 border-blue-500/20">
        <div className="flex items-start gap-3">
          <svg className="w-5 h-5 text-blue-500 mt-0.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-foreground mb-1">{t('publish.reviewNotice.title')}</h3>
            <p className="text-sm text-muted-foreground">{t('publish.reviewNotice.description')}</p>
          </div>
        </div>
      </Card>

      <Card className="p-8 space-y-8">
        <div className="space-y-3">
          <Label htmlFor="namespace" className="text-sm font-semibold font-heading">{t('publish.namespace')}</Label>
          {isLoadingNamespaces ? (
            <div className="h-11 animate-shimmer rounded-lg" />
          ) : (
            <Select
              value={normalizeSelectValue(namespaceSlug) ?? (isPrivate ? PRIVATE_NAMESPACE_SLUG : EMPTY_NAMESPACE_VALUE)}
              onValueChange={(value) => {
                if (isPrivate) return
                setNamespaceSlug(value === EMPTY_NAMESPACE_VALUE ? '' : value)
              }}
              disabled={isPrivate}
            >
              <SelectTrigger id="namespace">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {!isPrivate && (
                  <SelectItem value={EMPTY_NAMESPACE_VALUE}>{t('publish.selectNamespace')}</SelectItem>
                )}
                {isPrivate && (
                  <SelectItem value={PRIVATE_NAMESPACE_SLUG}>Private (@{PRIVATE_NAMESPACE_SLUG})</SelectItem>
                )}
                {!isPrivate && namespaces?.map((ns) => (
                  <SelectItem key={ns.id} value={ns.slug}>
                    {ns.displayName} (@{ns.slug})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

        <div className="space-y-3">
          <Label htmlFor="visibility" className="text-sm font-semibold font-heading">{t('publish.visibility')}</Label>
          <Select value={visibility} onValueChange={handleVisibilityChange}>
            <SelectTrigger id="visibility">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem
                value="PUBLIC"
                description={t('publish.visibilityDescriptions.public')}
              >
                {t('publish.visibilityOptions.public')}
              </SelectItem>
              <SelectItem
                value="NAMESPACE_ONLY"
                description={t('publish.visibilityDescriptions.namespaceOnly')}
              >
                {namespaceOnlyLabel}
              </SelectItem>
              <SelectItem
                value="PRIVATE"
                description={t('publish.visibilityDescriptions.private')}
              >
                {t('publish.visibilityOptions.private')}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-3">
          <Label className="text-sm font-semibold font-heading">{t('publish.file')}</Label>
          <UploadZone
            key={`upload-${uploadZoneNonce}-${selectedFile ? `${selectedFile.name}-${selectedFile.lastModified}` : 'empty'}`}
            onFileSelect={handleFileSelect}
            onFileRejected={handleFileRejected}
            disabled={publishMutation.isPending}
          />
          {fileError && (
            <div
              role="alert"
              className="rounded-lg border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm"
            >
              <p className="font-medium text-destructive">{fileError.title}</p>
              <p className="mt-1 text-muted-foreground">{fileError.description}</p>
            </div>
          )}
          {selectedFile && (
            <div className="flex items-center justify-between gap-3 rounded-lg border border-border/60 bg-secondary/30 px-4 py-3">
              <div className="min-w-0 text-sm text-muted-foreground flex items-center gap-2">
                <svg className="w-4 h-4 text-emerald-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
                <span className="truncate">
                  {selectedFile.name} ({formatFileSize(selectedFile.size)})
                </span>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleRemoveSelectedFile}
                disabled={publishMutation.isPending}
              >
                {t('publish.removeSelectedFile')}
              </Button>
            </div>
          )}
        </div>

        <Button
          className="w-full text-primary-foreground disabled:text-primary-foreground"
          size="lg"
          onClick={handlePublish}
          disabled={!selectedFile || !namespaceSlug || publishMutation.isPending}
        >
          {publishMutation.isPending ? t('publish.publishing') : t('publish.confirm')}
        </Button>
      </Card>

      <ConfirmDialog
        open={warningDialogOpen}
        onOpenChange={setWarningDialogOpen}
        title={t('publish.warningConfirmTitle')}
        description={(
          <div className="space-y-3 text-left">
            <p>{t('publish.warningConfirmDescription')}</p>
            {precheckWarnings.length > 0 && (
              <ul className="list-disc space-y-1 pl-5">
                {precheckWarnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        confirmText={t('publish.warningConfirmContinue')}
        cancelText={t('publish.warningConfirmCancel')}
        onConfirm={() => publishSkill(true)}
      />
    </div>
  )
}