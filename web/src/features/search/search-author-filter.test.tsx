/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SearchAuthorFilter } from './search-author-filter.tsx'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

describe('SearchAuthorFilter', () => {
  afterEach(() => {
    cleanup()
  })

  it('does not commit Enter while IME composition is active', () => {
    const onCommit = vi.fn()
    render(
      <SearchAuthorFilter value="" draft="zhang" onDraftChange={() => {}} onCommit={onCommit} />,
    )
    const input = screen.getByLabelText('search.authorFilterLabel')
    fireEvent.compositionStart(input)
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true })
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('commits a trimmed name on a real Enter', () => {
    const onCommit = vi.fn()
    render(
      <SearchAuthorFilter value="" draft="  张三  " onDraftChange={() => {}} onCommit={onCommit} />,
    )
    fireEvent.keyDown(screen.getByLabelText('search.authorFilterLabel'), { key: 'Enter', keyCode: 13 })
    expect(onCommit).toHaveBeenCalledWith('张三')
  })

  it('clears on X click without first committing the draft via blur', () => {
    const onCommit = vi.fn()
    render(
      <SearchAuthorFilter value="张三" draft="张三" onDraftChange={() => {}} onCommit={onCommit} />,
    )
    const clear = screen.getByLabelText('search.authorFilterClear')
    fireEvent.mouseDown(clear)
    fireEvent.click(clear)
    expect(onCommit).toHaveBeenCalledTimes(1)
    expect(onCommit).toHaveBeenCalledWith('')
  })
})
