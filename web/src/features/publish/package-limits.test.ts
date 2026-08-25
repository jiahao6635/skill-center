import { describe, expect, it } from 'vitest'
import { isPackageOverSizeLimit, MAX_PACKAGE_BYTES } from './package-limits.ts'

describe('package size limits', () => {
  it('allows a package at the 100MB boundary', () => {
    expect(isPackageOverSizeLimit(MAX_PACKAGE_BYTES)).toBe(false)
  })

  it('rejects a package larger than 100MB', () => {
    expect(isPackageOverSizeLimit(MAX_PACKAGE_BYTES + 1)).toBe(true)
    expect(isPackageOverSizeLimit(179874.2 * 1024)).toBe(true)
  })
})
