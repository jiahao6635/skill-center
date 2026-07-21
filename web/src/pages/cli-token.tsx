import { useState, useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Card } from '@/shared/ui/card.tsx'
import { getCurrentUser, tokenApi } from '@/api/client.ts'
import type { User } from '@/api/types.ts'

/**
 * CLI Token Page — simplified authentication flow for AI agents.
 *
 * Flow:
 * 1. User opens this page (e.g., from QoderWork)
 * 2. If not logged in → redirect to login
 * 3. If logged in → auto-create a token and display it
 * 4. AI agent reads the token via browser automation
 *
 * The token is displayed in a <pre> element with id="cli-token" for easy extraction.
 */
export function CliTokenPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [token, setToken] = useState<string | null>(null)
  const [expiresAt, setExpiresAt] = useState<string>('')
  const [error, setError] = useState<string>('')

  useEffect(() => {
    getCurrentUser()
      .then((currentUser) => setUser(currentUser))
      .catch(() => setUser(null))
  }, [])

  useEffect(() => {
    if (user === undefined) return
    if (user === null) {
      // Not logged in — redirect to login with returnTo
      navigate({ to: '/login', search: { returnTo: '/cli/token' } })
      return
    }
    // Logged in — create token with 7-day expiration
    const expires = new Date()
    expires.setDate(expires.getDate() + 7)
    setExpiresAt(expires.toLocaleDateString())
    tokenApi
      .createToken({
        name: 'CLI Auto Token',
        scopes: ['skill:read', 'skill:publish'],
        expiresAt: expires.toISOString(),
      })
      .then((response) => setToken(response.token))
      .catch((err) => setError(err instanceof Error ? err.message : 'Token creation failed'))
  }, [user, navigate])

  // Loading state
  if (user === undefined) {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4">
        <Card className="w-full max-w-md p-8 space-y-6 text-center">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-primary to-accent items-center justify-center shadow-glow mb-2 mx-auto">
            <svg className="w-8 h-8 text-primary-foreground animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold font-heading">{t('cliToken.loading')}</h1>
          <p className="text-muted-foreground">{t('cliToken.loadingDesc')}</p>
        </Card>
      </div>
    )
  }

  // Error state
  if (error) {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
        <Card className="w-full max-w-md p-8 space-y-6">
          <div className="text-center space-y-3">
            <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-red-500 to-red-600 items-center justify-center shadow-glow mb-2 mx-auto">
              <svg className="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold font-heading">{t('cliToken.error')}</h1>
            <p className="text-muted-foreground">{error}</p>
          </div>
        </Card>
      </div>
    )
  }

  // Token created — display it
  if (token) {
    return (
      <div className="min-h-[70vh] flex items-center justify-center p-4 animate-fade-up">
        <Card className="w-full max-w-lg p-8 space-y-6">
          <div className="text-center space-y-3">
            <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-emerald-500 to-emerald-600 items-center justify-center shadow-glow mb-2 mx-auto">
              <svg className="w-8 h-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold font-heading">{t('cliToken.success')}</h1>
            <p className="text-muted-foreground">{t('cliToken.successDesc')}</p>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium">{t('cliToken.tokenLabel')}</label>
              <span className="text-xs text-muted-foreground">{t('cliToken.expiresAt', { date: expiresAt })}</span>
            </div>
            <pre
              id="cli-token"
              data-token={token}
              className="p-4 bg-muted rounded-lg text-sm font-mono break-all select-all whitespace-pre-wrap"
            >
              {token}
            </pre>
          </div>

          <div className="p-4 bg-blue-50 dark:bg-blue-950/20 rounded-lg">
            <p className="text-sm text-blue-800 dark:text-blue-200">
              {t('cliToken.usageHint')}
            </p>
            <code className="block mt-2 p-2 bg-background rounded text-xs font-mono">
              npx clawhub login --token {token.substring(0, 10)}...
            </code>
          </div>
        </Card>
      </div>
    )
  }

  return null
}
