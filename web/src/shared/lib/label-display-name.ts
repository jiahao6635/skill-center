export type LabelDisplayTranslation = {
  locale?: string
  displayName?: string
}

export function resolveLabelDisplayName(
  translations: LabelDisplayTranslation[] | undefined,
  locale: string,
  fallback: string,
): string {
  const items = translations ?? []
  const normalizedLocale = normalizeLocale(locale)
  const exact = items.find((item) => normalizeLocale(item.locale) === normalizedLocale)
  if (exact?.displayName?.trim()) {
    return exact.displayName.trim()
  }

  const language = languageOf(normalizedLocale)
  const languageMatch = items.find((item) => languageOf(normalizeLocale(item.locale)) === language)
  if (languageMatch?.displayName?.trim()) {
    return languageMatch.displayName.trim()
  }

  const first = items.find((item) => item.displayName?.trim())
  return first?.displayName?.trim() || fallback
}

function normalizeLocale(value: string | undefined): string {
  return (value ?? '').trim().replace(/_/g, '-').toLowerCase()
}

function languageOf(normalizedLocale: string): string {
  const separator = normalizedLocale.indexOf('-')
  return separator < 0 ? normalizedLocale : normalizedLocale.slice(0, separator)
}
