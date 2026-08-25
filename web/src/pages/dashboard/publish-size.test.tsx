/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PublishPage } from './publish.tsx'

const useSearchMock = vi.fn()
const toastError = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useSearch: () => useSearchMock(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, values?: Record<string, unknown>) => {
        const templates: Record<string, string> = {
          'publish.packageTooLargeTitle': 'Skill package is too large',
          'publish.packageTooLargeDescription': '{{name}} is {{size}}. The limit is {{limit}}.',
          'publish.packageTooLargeUnknownSize': 'over the limit',
          'publish.confirm': 'Confirm Publish',
          'publish.removeSelectedFile': 'Remove selected file',
          'upload.dragHint': 'Drag a ZIP file here, or click to select',
          'upload.formatHint': 'Only .zip format supported',
          'upload.limitsHint': 'Package ≤ {{packageSize}}, each file ≤ {{fileSize}}, up to {{fileCount}} files',
          'upload.dropHint': 'Drop to upload...',
        }
        const template = templates[key] ?? key
        return Object.entries(values ?? {}).reduce(
          (result, [name, value]) => result.split(`{{${name}}}`).join(String(value)),
          template,
        )
      },
    }),
  }
})

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  usePublishSkill: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useMyNamespaces: () => ({ data: [], isLoading: false }),
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: {
    success: vi.fn(),
    error: (...args: unknown[]) => toastError(...args),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

function createZipFile(name: string, size: number): File {
  const file = new File(['pk'], name, { type: 'application/zip' })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('PublishPage package size UX', () => {
  beforeEach(() => {
    toastError.mockReset()
    useSearchMock.mockReturnValue({})
  })

  afterEach(() => {
    cleanup()
  })

  it('shows package limits and rejects an oversized zip with a readable size', async () => {
    render(<PublishPage />)

    expect(screen.getByText('Package ≤ 100 MB, each file ≤ 10 MB, up to 500 files')).toBeTruthy()

    const input = document.querySelector('input[type="file"]')
    expect(input).toBeTruthy()

    fireEvent.change(input as HTMLInputElement, {
      target: {
        files: [createZipFile('tobid-platform-regression.zip', Math.round(179874.2 * 1024))],
      },
    })

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('Skill package is too large')
    })
    expect(screen.getByRole('alert').textContent).toContain('tobid-platform-regression.zip is 175.7 MB')
    expect(screen.getByRole('alert').textContent).toContain('The limit is 100 MB')
    expect(screen.getByRole('button', { name: 'Confirm Publish' })).toHaveProperty('disabled', true)
    expect(toastError).toHaveBeenCalled()
  })

  it('shows a selected valid zip with MB/KB instead of raw kilobytes', async () => {
    render(<PublishPage />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, {
      target: { files: [createZipFile('ok.zip', 2 * 1024 * 1024)] },
    })

    await waitFor(() => {
      expect(screen.getByText('ok.zip (2 MB)')).toBeTruthy()
    })
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
