import { afterEach, describe, expect, it, vi } from 'vitest'
import { isPackageOverSizeLimit, MAX_PACKAGE_BYTES } from './package-limits.ts'

describe('package size limits', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('allows a package at the 100MB boundary', () => {
    expect(isPackageOverSizeLimit(MAX_PACKAGE_BYTES)).toBe(false)
  })

  it('rejects a package larger than 100MB', () => {
    expect(isPackageOverSizeLimit(MAX_PACKAGE_BYTES + 1)).toBe(true)
    expect(isPackageOverSizeLimit(179874.2 * 1024)).toBe(true)
  })

  it('uses the deployment single-file limit and falls back for invalid values', async () => {
    vi.stubGlobal('window', { __SKILLHUB_RUNTIME_CONFIG__: { maxSingleFileSize: '52428800' } })
    vi.resetModules()
    expect((await import('./package-limits.ts')).MAX_SINGLE_FILE_BYTES).toBe(50 * 1024 * 1024)
    vi.stubGlobal('window', { __SKILLHUB_RUNTIME_CONFIG__: { maxSingleFileSize: 'invalid' } })
    vi.resetModules()
    expect((await import('./package-limits.ts')).MAX_SINGLE_FILE_BYTES).toBe(10 * 1024 * 1024)
  })
})
