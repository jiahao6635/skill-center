/**
 * Publish package limits. Keep in sync with the backend deployment settings.
 */
export const MAX_FILE_COUNT = 500
const configuredSingleFileBytes = Number(
  typeof window === 'undefined' ? undefined : window.__SKILLHUB_RUNTIME_CONFIG__?.maxSingleFileSize,
)
export const MAX_SINGLE_FILE_BYTES = Number.isSafeInteger(configuredSingleFileBytes) && configuredSingleFileBytes > 0
  ? configuredSingleFileBytes
  : 10 * 1024 * 1024
export const MAX_PACKAGE_BYTES = 100 * 1024 * 1024

export function isPackageOverSizeLimit(sizeBytes: number): boolean {
  return sizeBytes > MAX_PACKAGE_BYTES
}
