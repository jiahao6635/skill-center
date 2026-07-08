import { ApiError } from '@/api/client.ts'

export function shouldFallbackVisibleLabelsError(error: unknown) {
  return error instanceof ApiError
}
