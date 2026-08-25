import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useDropzone, type FileRejection } from 'react-dropzone'
import { cn } from '@/shared/lib/utils.ts'
import { formatFileSize } from '@/shared/lib/file-size.ts'
import {
  MAX_FILE_COUNT,
  MAX_PACKAGE_BYTES,
  MAX_SINGLE_FILE_BYTES,
} from './package-limits.ts'

interface UploadZoneProps {
  onFileSelect: (file: File) => void
  onFileRejected?: (reason: 'too-large' | 'invalid-type', file?: File) => void
  disabled?: boolean
}

/**
 * Provides the publish page dropzone for uploading one zip package at a time.
 * Size-limit UX lives in the publish page so the surrounding form can show a
 * persistent message; this zone only reports accepted and rejected files.
 */
export function UploadZone({ onFileSelect, onFileRejected, disabled }: UploadZoneProps) {
  const { t } = useTranslation()
  const onDrop = useCallback(
    (acceptedFiles: File[]) => {
      if (acceptedFiles.length > 0) {
        onFileSelect(acceptedFiles[0])
      }
    },
    [onFileSelect]
  )
  const onDropRejected = useCallback(
    (rejections: FileRejection[]) => {
      const tooLarge = rejections.find((rejection) =>
        rejection.errors.some((error) => error.code === 'file-too-large')
      )
      if (tooLarge) {
        onFileRejected?.('too-large', tooLarge.file)
        return
      }
      const invalidType = rejections.find((rejection) =>
        rejection.errors.some((error) => error.code === 'file-invalid-type')
      )
      if (invalidType) {
        onFileRejected?.('invalid-type', invalidType.file)
      }
    },
    [onFileRejected]
  )

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    onDropRejected,
    accept: {
      'application/zip': ['.zip'],
    },
    maxFiles: 1,
    maxSize: MAX_PACKAGE_BYTES,
    disabled,
  })

  return (
    <div
      {...getRootProps()}
      className={cn(
        'upload-zone rounded-xl p-10 text-center cursor-pointer transition-all duration-300',
        isDragActive && 'border-primary bg-primary/5 scale-[1.01]',
        disabled && 'opacity-50 cursor-not-allowed'
      )}
    >
      <input {...getInputProps()} />
      <div className="flex flex-col items-center gap-3">
        <div className="w-14 h-14 rounded-2xl bg-secondary/60 flex items-center justify-center">
          <svg
            className={cn(
              'w-7 h-7 upload-zone-icon transition-colors',
              isDragActive && 'text-primary'
            )}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
            />
          </svg>
        </div>
        {isDragActive ? (
          <p className="text-sm text-primary font-medium">{t('upload.dropHint')}</p>
        ) : (
          <>
            <p className="text-sm font-medium text-foreground">{t('upload.dragHint')}</p>
            <p className="text-xs text-muted-foreground">{t('upload.formatHint')}</p>
            <p className="text-xs text-muted-foreground">
              {t('upload.limitsHint', {
                packageSize: formatFileSize(MAX_PACKAGE_BYTES),
                fileSize: formatFileSize(MAX_SINGLE_FILE_BYTES),
                fileCount: MAX_FILE_COUNT,
              })}
            </p>
          </>
        )}
      </div>
    </div>
  )
}
