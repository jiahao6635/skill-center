const DAY = 86_400_000
export const usageProducts = ['', 'qoder', 'qoder_ide', 'qoderwork', 'unknown'] as const
export type UsageProduct = typeof usageProducts[number]
export type UsagePreset = 'today' | '7d' | '30d' | 'month' | 'custom'
export interface SkillUsageSearch {
  preset: UsagePreset
  start: string
  end: string
  email: string
  name: string
  product: UsageProduct
  tab: 'skills' | 'users'
  skill: string
  page: number
}

export function presetDates(preset: UsagePreset, now = new Date()) {
  const end = new Date(now.getTime() + 8 * 3_600_000).toISOString().slice(0, 10)
  const midnight = Date.parse(`${end}T00:00:00Z`)
  const start = preset === 'month' ? `${end.slice(0, 7)}-01`
    : new Date(midnight - (preset === 'today' ? 0 : preset === '7d' ? 6 : 29) * DAY).toISOString().slice(0, 10)
  return { start, end }
}

export function parseSkillUsageSearch(input: Record<string, unknown>): SkillUsageSearch {
  const presets: UsagePreset[] = ['today', '7d', '30d', 'month', 'custom']
  const preset = presets.includes(input.preset as UsagePreset) ? input.preset as UsagePreset : '30d'
  const dates = presetDates(preset)
  const text = (key: string, fallback = '') => typeof input[key] === 'string' ? (input[key] as string).slice(0, 512) : fallback
  const page = Number(input.page)
  return {
    preset, start: text('start', dates.start), end: text('end', dates.end),
    email: text('email').trim().toLowerCase(), name: text('name'),
    product: usageProducts.includes(input.product as UsageProduct) ? input.product as UsageProduct : '',
    tab: input.tab === 'users' ? 'users' : 'skills', skill: text('skill'),
    page: Number.isSafeInteger(page) && page >= 0 ? page : 0,
  }
}

export function usageRange(start: string, end: string) {
  const valid = (date: string) => /^\d{4}-\d{2}-\d{2}$/.test(date)
    && Number.isFinite(Date.parse(`${date}T00:00:00Z`))
    && new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date
  if (!valid(start) || !valid(end)) return null
  const from = Date.parse(`${start}T00:00:00+08:00`)
  const to = Date.parse(`${end}T00:00:00+08:00`) + DAY
  if (to <= from || to - from > 366 * DAY) return null
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString() }
}

export function formatUsageTime(value: string | undefined, language: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(language, {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value))
}
