import { useTranslation } from 'react-i18next'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries.ts'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/select.tsx'
import { buildNamespaceFilterOptions } from './build-namespace-filter-options.ts'

const ALL_NAMESPACES_VALUE = '__all_namespaces__'

export function SearchNamespaceFilter(props: {
  value: string
  onChange: (slug: string) => void
  isAuthenticated: boolean
}) {
  const { value, onChange, isAuthenticated } = props
  const { t } = useTranslation()
  const { data: mine } = useMyNamespaces({ enabled: isAuthenticated })
  const options = buildNamespaceFilterOptions({
    currentSlug: value,
    mine: isAuthenticated ? (mine ?? []) : [],
  })
  const triggerLabel = value === ''
    ? t('search.namespaceFilter', { namespace: 'global' })
    : t('search.namespaceFilter', { namespace: value })

  return (
    <Select
      value={value || ALL_NAMESPACES_VALUE}
      onValueChange={(next) => {
        onChange(next === ALL_NAMESPACES_VALUE ? '' : next)
      }}
    >
      <SelectTrigger
        aria-label={t('search.namespaceFilterLabel')}
        className="h-8 w-[12rem] shrink-0 py-0"
      >
        <SelectValue>{triggerLabel}</SelectValue>
      </SelectTrigger>
      {/* item-aligned: shared popper Viewport height tracks the h-8 trigger and clips the list */}
      <SelectContent position="item-aligned" className="max-h-72">
        {options.pinned.map((item) => (
          item.group === 'all' ? (
            <SelectItem
              key={ALL_NAMESPACES_VALUE}
              value={ALL_NAMESPACES_VALUE}
              textValue={t('search.namespaceFilterAll')}
            >
              {t('search.namespaceFilterAll')}
            </SelectItem>
          ) : (
            <SelectItem
              key={item.slug}
              value={item.slug}
              textValue={t('search.namespaceFilter', { namespace: item.slug })}
            >
              {t('search.namespaceFilter', { namespace: item.slug })}
              <span className="ml-2 text-xs text-muted-foreground">
                {t('search.namespaceGlobalHint')}
              </span>
            </SelectItem>
          )
        ))}
        {options.mine.length > 0 ? (
          <SelectGroup>
            <SelectLabel>{t('search.namespaceFilterMine')}</SelectLabel>
            {options.mine.map((item) => (
              <SelectItem
                key={item.slug}
                value={item.slug}
                textValue={t('search.namespaceFilter', { namespace: item.slug })}
              >
                {t('search.namespaceFilter', { namespace: item.slug })}
              </SelectItem>
            ))}
          </SelectGroup>
        ) : null}
        {options.current.length > 0 ? (
          <SelectGroup>
            <SelectLabel>{t('search.namespaceFilterCurrent')}</SelectLabel>
            {options.current.map((item) => (
              <SelectItem
                key={item.slug}
                value={item.slug}
                textValue={t('search.namespaceFilter', { namespace: item.slug })}
              >
                {t('search.namespaceFilter', { namespace: item.slug })}
              </SelectItem>
            ))}
          </SelectGroup>
        ) : null}
      </SelectContent>
    </Select>
  )
}
