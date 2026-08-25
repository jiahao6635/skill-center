import { describe, expect, it } from 'vitest'
import { formatFileSize } from './file-size.ts'

describe('formatFileSize', () => {
  it('formats bytes, kilobytes, and megabytes', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(1024)).toBe('1 KB')
    expect(formatFileSize(10 * 1024 * 1024)).toBe('10 MB')
    expect(formatFileSize(100 * 1024 * 1024)).toBe('100 MB')
  })

  it('converts the publish-page KB display into megabytes', () => {
    expect(formatFileSize(179874.2 * 1024)).toBe('175.7 MB')
  })
})
