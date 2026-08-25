/**
 * Publish package limits. Keep in sync with SkillPackagePolicy and
 * skillhub.publish in application.yml.
 */
export const MAX_FILE_COUNT = 500
export const MAX_SINGLE_FILE_BYTES = 10 * 1024 * 1024
export const MAX_PACKAGE_BYTES = 100 * 1024 * 1024

export function isPackageOverSizeLimit(sizeBytes: number): boolean {
  return sizeBytes > MAX_PACKAGE_BYTES
}
