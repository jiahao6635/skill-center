import { describe, expect, it } from 'vitest'
import { parseSkillUsageSearch, presetDates, usageRange, formatUsageTime } from './skill-usage-state.ts'

describe('Skill usage reporting dates and URL state', () => {
  it('uses Beijing calendar days across UTC midnight and month/year boundaries', () => {
    expect(presetDates('today', new Date('2026-09-13T16:00:00Z'))).toEqual({ start: '2026-09-14', end: '2026-09-14' })
    expect(presetDates('7d', new Date('2026-01-01T00:00:00Z'))).toEqual({ start: '2025-12-26', end: '2026-01-01' })
    expect(presetDates('month', new Date('2026-09-30T16:00:00Z'))).toEqual({ start: '2026-10-01', end: '2026-10-01' })
  })
  it('converts an inclusive date range to exclusive UTC instants', () => {
    expect(usageRange('2026-09-14', '2026-09-14')).toEqual({ from: '2026-09-13T16:00:00.000Z', to: '2026-09-14T16:00:00.000Z' })
    expect(usageRange('2024-01-01', '2024-12-31')).not.toBeNull()
    expect(usageRange('2024-01-01', '2025-01-01')).toBeNull()
  })
  it('rejects missing, impossible and reversed dates', () => {
    for (const [start, end] of [['', '2026-01-01'], ['2026-02-30', '2026-03-01'], ['2026-09-14', '2026-09-13']]) expect(usageRange(start, end)).toBeNull()
  })
  it('restores filters and safely normalizes invalid URL values', () => {
    const state = parseSkillUsageSearch({ email: ' A@Test ', tab: 'users', page: '2', product: 'qoder_ide', start: '2026-09-01', end: '2026-09-14' })
    expect(state).toMatchObject({ email: 'a@test', page: 2, tab: 'users', product: 'qoder_ide', start: '2026-09-01' })
    expect(parseSkillUsageSearch({ page: -2, product: 'invalid', tab: 'bad' })).toMatchObject({ page: 0, product: '', tab: 'skills' })
    expect(formatUsageTime('2026-09-13T16:00:00Z', 'zh-CN')).toContain('2026/09/14')
  })
})
