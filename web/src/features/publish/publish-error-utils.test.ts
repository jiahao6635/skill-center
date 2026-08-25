import { describe, expect, it } from 'vitest'
import {
  extractPrecheckWarnings,
  isFrontmatterFailureMessage,
  isPackageTooLargeError,
  isPrecheckConfirmationMessage,
  isPrecheckFailureMessage,
  isVersionExistsMessage,
} from './publish-error-utils.ts'

describe('publish-error-utils', () => {
  it('detects confirmation-required warnings in English', () => {
    expect(isPrecheckConfirmationMessage('Pre-publish warnings require confirmation before publishing:\n- warning')).toBe(true)
  })

  it('detects confirmation-required warnings in Chinese', () => {
    expect(isPrecheckConfirmationMessage('预发布发现以下风险提醒，确认后仍可继续发布：\n- 风险提醒')).toBe(true)
  })

  it('extracts warning lines from a confirmation message', () => {
    expect(extractPrecheckWarnings(
      'Pre-publish warnings require confirmation before publishing:\n- Disallowed file extension: malware.exe\n- SKILL.md line 5 contains a value that looks like a secret or token.'
    )).toEqual([
      'Disallowed file extension: malware.exe',
      'SKILL.md line 5 contains a value that looks like a secret or token.',
    ])
  })

  it('keeps existing blocking precheck detection', () => {
    expect(isPrecheckFailureMessage('Pre-publish validation failed: validator blocked publish')).toBe(true)
  })

  it('keeps version and frontmatter detection helpers', () => {
    expect(isVersionExistsMessage('Version already exists')).toBe(true)
    expect(isFrontmatterFailureMessage('Invalid SKILL.md frontmatter')).toBe(true)
  })

  it('detects oversized package errors from status or message', () => {
    expect(isPackageTooLargeError(413)).toBe(true)
    expect(isPackageTooLargeError(400, 'Package too large: 184191181 bytes (max: 104857600)')).toBe(true)
    expect(isPackageTooLargeError(400, '技能包超过 100MB 大小限制，请缩小后再上传。')).toBe(true)
    expect(isPackageTooLargeError(400, 'Invalid request')).toBe(false)
  })
})
