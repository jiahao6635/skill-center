/** @vitest-environment jsdom */

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './select.tsx'

describe('select option descriptions', () => {
  afterEach(() => {
    cleanup()
  })

  it('keeps the trigger on the option title instead of the description', () => {
    render(
      <Select value="NAMESPACE_ONLY">
        <SelectTrigger aria-label="visibility">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem
            value="NAMESPACE_ONLY"
            description="仅该命名空间成员可见，通过审核后发布。"
          >
            仅命名空间
          </SelectItem>
        </SelectContent>
      </Select>
    )

    const trigger = screen.getByRole('combobox', { name: 'visibility' })
    expect(trigger.textContent).toContain('仅命名空间')
    expect(trigger.textContent).not.toContain('仅该命名空间成员可见')
  })
})
