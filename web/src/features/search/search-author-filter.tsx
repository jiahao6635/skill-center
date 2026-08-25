import { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Input } from '@/shared/ui/input.tsx'
import { MAX_AUTHOR_NAME_LENGTH } from './parse-search-page-search.ts'

const AUTHOR_INPUT_ID = 'search-author-filter'

export function SearchAuthorFilter(props: {
  value: string
  draft: string
  onDraftChange: (name: string) => void
  onCommit: (name: string) => void
}) {
  const { value, draft, onDraftChange, onCommit } = props
  const { t } = useTranslation()
  const composingRef = useRef(false)
  const showClear = Boolean(draft || value)

  const commitDraft = () => {
    onCommit(draft.trim())
  }

  return (
    <div className="flex h-8 shrink-0 items-center gap-1.5">
      <label
        htmlFor={AUTHOR_INPUT_ID}
        className="select-none text-sm text-muted-foreground"
      >
        {t('search.authorFilterFieldLabel')}
      </label>
      <div className="relative h-8 w-24 shrink-0">
        <Input
          id={AUTHOR_INPUT_ID}
          type="text"
          value={draft}
          maxLength={MAX_AUTHOR_NAME_LENGTH}
          placeholder={t('search.authorFilterPlaceholder')}
          aria-label={t('search.authorFilterLabel')}
          aria-description={t('search.authorFilterExactHint')}
          title={t('search.authorFilterExactHint')}
          className="h-8 w-24 py-0 px-2 pr-7 text-sm"
          onChange={(event) => onDraftChange(event.target.value)}
          onCompositionStart={() => {
            composingRef.current = true
          }}
          onCompositionEnd={() => {
            composingRef.current = false
          }}
          onKeyDown={(event) => {
            if (event.key !== 'Enter') {
              return
            }
            if (event.nativeEvent.isComposing || composingRef.current || event.keyCode === 229) {
              return
            }
            event.preventDefault()
            commitDraft()
          }}
          onBlur={(event) => {
            const next = event.relatedTarget
            if (next instanceof Node && event.currentTarget.closest('[data-search-toolbar]')?.contains(next)) {
              return
            }
            if (draft.trim() !== value) {
              commitDraft()
            }
          }}
        />
        {showClear ? (
          <button
            type="button"
            className="absolute right-1 top-1/2 inline-flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-secondary/70 hover:text-foreground"
            aria-label={t('search.authorFilterClear')}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onCommit('')}
          >
            <X className="h-3 w-3" />
          </button>
        ) : null}
      </div>
    </div>
  )
}
