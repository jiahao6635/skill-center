import { describe, expect, it } from 'vitest'
import {
  SELECT_CONTENT_CLASS_NAME,
  SELECT_ITEM_CLASS_NAME,
  SELECT_SCROLL_BUTTON_CLASS_NAME,
  SELECT_TRIGGER_CLASS_NAME,
  SELECT_VIEWPORT_POPPER_CLASS_NAME,
  normalizeSelectValue,
} from './select.tsx'

describe('shared select contract', () => {
  it('keeps the trigger aligned with the existing input styling language', () => {
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('h-11')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('rounded-lg')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('border-border/60')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('bg-secondary/50')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('text-left')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('focus-visible:outline-none')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('focus-visible:ring-2')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('focus-visible:ring-primary/40')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('focus-visible:border-primary/50')
  })

  it('keeps selected labels left-aligned and truncated only when they overflow', () => {
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('[&>span]:line-clamp-1')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('[&>span]:min-w-0')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('[&>span]:flex-1')
    expect(SELECT_TRIGGER_CLASS_NAME).toContain('[&>span]:text-left')
  })

  it('lets popper lists grow taller than the trigger instead of clipping to it', () => {
    expect(SELECT_VIEWPORT_POPPER_CLASS_NAME).toContain('min-h-[var(--radix-select-trigger-height)]')
    expect(SELECT_VIEWPORT_POPPER_CLASS_NAME).not.toMatch(/(?:^|\s)h-\[var\(--radix-select-trigger-height\)\]/)
    expect(SELECT_VIEWPORT_POPPER_CLASS_NAME).toContain('w-[var(--radix-select-trigger-width)]')
    expect(SELECT_VIEWPORT_POPPER_CLASS_NAME).toContain('min-w-[var(--radix-select-trigger-width)]')
  })

  it('uses themed panel and item classes for the floating listbox', () => {
    expect(SELECT_CONTENT_CLASS_NAME).toContain('bg-popover')
    expect(SELECT_CONTENT_CLASS_NAME).toContain('text-popover-foreground')
    expect(SELECT_ITEM_CLASS_NAME).toContain('focus:bg-accent')
    expect(SELECT_ITEM_CLASS_NAME).toContain('data-[disabled]:opacity-50')
  })

  it('keeps the dropdown and selected items visually discoverable', () => {
    expect(SELECT_CONTENT_CLASS_NAME).toContain('shadow-md')
    expect(SELECT_ITEM_CLASS_NAME).toContain('pl-8')
    expect(SELECT_ITEM_CLASS_NAME).toContain('rounded-md')
  })

  it('uses pointer cursors for expanded select interactions', () => {
    expect(SELECT_ITEM_CLASS_NAME).toContain('cursor-pointer')
    expect(SELECT_SCROLL_BUTTON_CLASS_NAME).toContain('cursor-pointer')
  })

  it('maps empty and nullish form state to an undefined Radix value', () => {
    expect(normalizeSelectValue('')).toBeUndefined()
    expect(normalizeSelectValue(null)).toBeUndefined()
    expect(normalizeSelectValue(undefined)).toBeUndefined()
    expect(normalizeSelectValue('PUBLIC')).toBe('PUBLIC')
  })
})
