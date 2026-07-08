import { useQuery } from '@tanstack/react-query'
import { authApi } from '@/api/client.ts'
import type { AuthMethod } from '@/api/types.ts'

/**
 * Loads the backend-advertised authentication methods for the current entry point.
 */
export function useAuthMethods(returnTo?: string) {
  return useQuery<AuthMethod[]>({
    queryKey: ['auth', 'methods', returnTo ?? ''],
    queryFn: () => authApi.getMethods(returnTo),
  })
}
