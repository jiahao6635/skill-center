import { describe, expect, it } from 'vitest'
import { resolveLabelDisplayName } from './label-display-name.ts'

const translations = [
  { locale: 'en', displayName: 'Code Generation' },
  { locale: 'zh-CN', displayName: '代码生成' },
]

describe('resolveLabelDisplayName', () => {
  it('matches zh UI language to zh-CN translations', () => {
    expect(resolveLabelDisplayName(translations, 'zh', 'code-generation')).toBe('代码生成')
  })

  it('matches zh-CN UI language to zh translations', () => {
    expect(resolveLabelDisplayName(
      [{ locale: 'zh', displayName: '官方' }],
      'zh-CN',
      'official',
    )).toBe('官方')
  })

  it('prefers an exact language tag over the language prefix', () => {
    expect(resolveLabelDisplayName(
      [
        { locale: 'zh', displayName: '官方' },
        { locale: 'zh-CN', displayName: '官方认证' },
      ],
      'zh-CN',
      'official',
    )).toBe('官方认证')
  })

  it('falls back to the first translation then the slug', () => {
    expect(resolveLabelDisplayName(
      [{ locale: 'zh-CN', displayName: '代码生成' }],
      'en',
      'code-generation',
    )).toBe('代码生成')
    expect(resolveLabelDisplayName([], 'zh', 'code-generation')).toBe('code-generation')
  })
})
