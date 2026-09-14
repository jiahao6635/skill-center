// @vitest-environment jsdom
import { useSyncExternalStore } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/shared/lib/api-error.ts'
import { parseSkillUsageSearch, type SkillUsageSearch } from '@/features/admin/skill-usage-state.ts'
import zh from '@/i18n/locales/zh.json'

const mocks = vi.hoisted(() => ({
  fetch: vi.fn(), auth: { user: { userId: 'admin' }, hasRole: (): boolean => true, isLoading: false },
  state: {} as SkillUsageSearch, listeners: new Set<() => void>(),
}))
vi.mock('@/features/auth/use-auth.ts', () => ({ useAuth: () => mocks.auth }))
vi.mock('@/api/client.ts', async () => ({ fetchJson: mocks.fetch, ApiError: (await import('@/shared/lib/api-error.ts')).ApiError }))
vi.mock('@tanstack/react-router', () => ({
  useSearch: () => useSyncExternalStore(callback => { mocks.listeners.add(callback); return () => { mocks.listeners.delete(callback) } }, () => mocks.state),
  useNavigate: () => ({ search }: { search: (previous: SkillUsageSearch) => SkillUsageSearch }) => { mocks.state = search(mocks.state); mocks.listeners.forEach(fn => fn()); return Promise.resolve() },
}))
vi.mock('react-i18next', async importOriginal => ({ ...await importOriginal<typeof import('react-i18next')>(), useTranslation: () => ({
  i18n: { language: 'zh-CN' },
  t: (key: string, values?: Record<string, unknown>) => {
    const result = key.split('.').reduce<unknown>((obj, part) => obj && typeof obj === 'object' ? (obj as Record<string, unknown>)[part] : undefined, zh)
    return String(result || key).replace(/{{(\w+)}}/g, (_, k: string) => String(values?.[k] ?? ''))
  },
}) }))
import { SkillUsagePage } from './skill-usage.tsx'

let client: QueryClient
const skill = { skillName: 'plugin:report', invocationCount: 12, userCount: 2, rank: 1, peakCount: 12, lastUsedAt: '2026-09-14T01:00:00Z', unlinked: false, downloadCount: 300, starCount: 12 }
const summary = { invocationCount: 12, userCount: 2, skillCount: 1, sessionCount: 3, queriedAt: '2026-09-14T01:00:00Z' }
function mount() { return render(<QueryClientProvider client={client}><SkillUsagePage /></QueryClientProvider>) }
beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mocks.state = parseSkillUsageSearch({ start: '2026-09-01', end: '2026-09-14' })
  mocks.auth = { user: { userId: 'admin' }, hasRole: () => true, isLoading: false }
  mocks.fetch.mockReset().mockImplementation(async (input: string) => {
    const url = new URL(input, 'http://localhost')
    if (url.pathname.endsWith('/summary')) return summary
    if (url.pathname.endsWith('/skills')) return { items: [{ ...skill, invocationCount: url.searchParams.has('email') ? 5 : 12 }], total: 1, page: 0, size: 20 }
    if (url.pathname.endsWith('/users')) return { items: [{ email: 'a@test', name: '测试用户', invocationCount: 5, skillCount: 1, rank: 1, lastUsedAt: skill.lastUsedAt }], total: 1, page: 0, size: 20 }
    if (url.pathname.endsWith('/user-options')) return [{ email: 'a@test', name: '测试用户' }, { email: 'other@test', name: '测试用户' }]
    return { items: [{ id: 1, event: { skill_name: skill.skillName, session_id: 'session-1', email: 'a@test', name: '测试用户', client_product: 'qoder_ide', trigger_mode: 'manual', occurred_at: skill.lastUsedAt } }], total: 1, page: 0, size: 20 }
  })
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { cleanup(); client.clear(); mocks.listeners.clear() })

describe('Skill usage dashboard', () => {
  it('shows registry counters and supports ranking → user → invocation details', async () => {
    mount()
    await screen.findAllByText('plugin:report')
    expect(screen.getAllByText('300').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: '用户排行' }))
    fireEvent.click((await screen.findAllByRole('button', { name: '查看使用情况' }))[0])
    await screen.findByText('该用户使用的 Skill')
    expect(mocks.state.email).toBe('a@test')
    expect(mocks.state.tab).toBe('skills')
    expect(mocks.state.start).toBe('2026-09-01')
    await waitFor(() => expect(screen.getAllByText('5').length).toBeGreaterThan(0))
    fireEvent.click(screen.getAllByRole('button', { name: '查看明细' })[0])
    expect(await screen.findByRole('dialog')).toBeTruthy()
    await screen.findByText('session-1')
    expect(screen.getByText('手动触发')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '关闭明细' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '清除用户筛选' }))
    await waitFor(() => expect(mocks.state.email).toBe(''))
  })
  it('searches users by name with keyboard selection and filters by exact email', async () => {
    mount()
    fireEvent.change(screen.getByRole('combobox', { name: '用户' }), { target: { value: '测试' } })
    await screen.findAllByRole('option', { name: /测试用户/ })
    const input = screen.getByRole('combobox', { name: '用户' })
    fireEvent.keyDown(input, { key: 'ArrowDown' }); fireEvent.keyDown(input, { key: 'ArrowDown' }); fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(mocks.state.email).toBe('other@test'))
    expect(mocks.fetch.mock.calls.some(([url]) => String(url).includes('email=other%40test'))).toBe(true)
  })
  it('keeps skill name search out of summary and restores URL state', async () => {
    mocks.state = parseSkillUsageSearch({ start: '2026-09-01', end: '2026-09-14', email: 'a@test', product: 'unknown', page: 2 })
    mount()
    await screen.findAllByText('plugin:report')
    fireEvent.change(screen.getByRole('textbox', { name: '搜索 Skill 名称…' }), { target: { value: 'report' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(mocks.state.skill).toBe('report'))
    expect(mocks.state.page).toBe(0)
    expect(mocks.state.product).toBe('unknown')
    expect(mocks.fetch.mock.calls.filter(([url]) => String(url).includes('/summary')).every(([url]) => !String(url).includes('search='))).toBe(true)
  })
  it('distinguishes errors from empty data and supports retry', async () => {
    mocks.fetch.mockRejectedValue(new Error('network'))
    mount()
    await screen.findAllByRole('alert')
    expect(screen.queryByText('当前条件下暂无已采集调用记录')).toBeNull()
    mocks.fetch.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    fireEvent.click(screen.getAllByRole('button', { name: '重试' })[1])
    await screen.findByText('当前条件下暂无已采集调用记录')
  })
  it('clears previously loaded data and caches when any endpoint denies permission', async () => {
    mount()
    await screen.findAllByText('plugin:report')
    mocks.fetch.mockRejectedValue(new ApiError('Forbidden', 403))
    fireEvent.click(screen.getByRole('button', { name: '刷新' }))
    await screen.findByText('无权访问。仅最高管理员可查看 Skill 使用统计。')
    expect(screen.queryByText('plugin:report')).toBeNull()
    await waitFor(() => expect(client.getQueryCache().findAll({ queryKey: ['admin', 'skill-usage'] })).toHaveLength(0))
  })
  it('does not fetch data for non-super-admin users or invalid dates', async () => {
    mocks.auth.hasRole = () => false
    const view = mount()
    expect(screen.getByRole('alert')).toBeTruthy()
    expect(mocks.fetch).not.toHaveBeenCalled()
    mocks.auth.hasRole = () => true
    mocks.state = parseSkillUsageSearch({ start: '2026-01-01', end: '2027-01-03', preset: 'custom' })
    await act(async () => view.rerender(<QueryClientProvider client={client}><SkillUsagePage /></QueryClientProvider>))
    expect(screen.getByText(/单次查询最多 366 天/)).toBeTruthy()
    expect(mocks.fetch).not.toHaveBeenCalled()
  })
})
