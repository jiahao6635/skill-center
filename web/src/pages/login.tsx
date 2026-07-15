import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown } from 'lucide-react'
import { getDirectAuthRuntimeConfig } from '@/api/client.ts'
import { LoginButton } from '@/features/auth/login-button.tsx'
import { SessionBootstrapEntry } from '@/features/auth/session-bootstrap-entry.tsx'
import { useAuthMethods } from '@/features/auth/use-auth-methods.ts'
import { usePasswordLogin } from '@/features/auth/use-password-login.ts'
import { Button } from '@/shared/ui/button.tsx'
import { Input } from '@/shared/ui/input.tsx'

/**
 * Authentication entry page.
 *
 * It combines password login, OAuth entry points, and optional session-bootstrap support while
 * preserving the route the user originally intended to visit.
 */
export function LoginPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const search = useSearch({ from: '/login' })
  const loginMutation = usePasswordLogin()
  const directAuthConfig = getDirectAuthRuntimeConfig()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showAdminLogin, setShowAdminLogin] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<{ username?: string, password?: string }>({})
  const isChinese = i18n.resolvedLanguage?.split('-')[0] === 'zh'
  const { data: authMethods } = useAuthMethods(search.returnTo)

  const returnTo = search.returnTo && search.returnTo.startsWith('/') ? search.returnTo : '/dashboard'
  const disabledMessage = search.reason === 'accountDisabled' ? t('apiError.auth.accountDisabled') : null
  const directMethod = directAuthConfig.provider
    ? authMethods?.find((method) =>
      method.methodType === 'DIRECT_PASSWORD' && method.provider === directAuthConfig.provider)
    : undefined
  const bootstrapMethod = authMethods?.find((method) => method.methodType === 'SESSION_BOOTSTRAP')

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmedUsername = username.trim()
    const nextFieldErrors: { username?: string, password?: string } = {}

    if (!trimmedUsername) {
      nextFieldErrors.username = t('login.usernameRequired')
    }
    if (!password) {
      nextFieldErrors.password = t('login.passwordRequired')
    }
    if (nextFieldErrors.username || nextFieldErrors.password) {
      setFieldErrors(nextFieldErrors)
      return
    }

    setFieldErrors({})
    try {
      await loginMutation.mutateAsync({ username: trimmedUsername, password })
      await navigate({ to: returnTo })
    } catch {
      // mutation state drives the error UI
    }
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <div className="w-full max-w-md space-y-8 animate-fade-up">
        <div className="text-center space-y-3">
          <div className="inline-flex w-16 h-16 rounded-2xl bg-gradient-to-br from-primary to-primary/70 items-center justify-center shadow-glow mb-4">
            <span className="text-primary-foreground font-bold text-2xl">S</span>
          </div>
          <h1 className="text-4xl font-bold font-heading text-foreground">{t('login.title')}</h1>
          <p className="text-muted-foreground text-lg">
            {t('login.subtitle')}
          </p>
        </div>

        <div className="glass-strong p-8 rounded-2xl">
          <div className="space-y-6">
            {disabledMessage ? (
              <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                {disabledMessage}
              </div>
            ) : null}
            <SessionBootstrapEntry
              methodDisplayName={bootstrapMethod?.displayName}
              onAuthenticated={() => navigate({ to: returnTo })}
            />

            {/* Primary: Feishu OAuth Login */}
            <div className="space-y-4">
              <LoginButton returnTo={returnTo} />
            </div>

            {/* Divider */}
            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <span className="w-full border-t" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-card px-2 text-muted-foreground">
                  {t('login.adminLoginDivider')}
                </span>
              </div>
            </div>

            {/* Secondary: Admin Password Login (collapsed by default) */}
            <div className="space-y-3">
              <button
                type="button"
                onClick={() => setShowAdminLogin(!showAdminLogin)}
                className="flex w-full items-center justify-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                <span>{t('login.adminLoginToggle')}</span>
                <ChevronDown className={`h-4 w-4 transition-transform ${showAdminLogin ? 'rotate-180' : ''}`} />
              </button>

              {showAdminLogin && (
                <form className="space-y-4 rounded-lg border border-border/50 bg-muted/30 p-4" onSubmit={handleSubmit}>
                  <p className="text-xs text-muted-foreground text-center">
                    {t('login.adminLoginHint')}
                  </p>
                  {directAuthConfig.enabled ? (
                    <p className="text-xs text-muted-foreground">
                      {t('login.passwordCompatHint', {
                        name: directMethod?.displayName ?? directAuthConfig.provider,
                      })}
                    </p>
                  ) : null}
                  <div className="space-y-2">
                    <label className="text-sm font-medium" htmlFor="username">{t('login.username')}</label>
                    <Input
                      id="username"
                      autoComplete="username"
                      value={username}
                      onChange={(event) => {
                        setUsername(event.target.value)
                        if (fieldErrors.username) {
                          setFieldErrors((current) => ({ ...current, username: undefined }))
                        }
                      }}
                      placeholder={t('login.usernamePlaceholder')}
                      aria-invalid={fieldErrors.username ? 'true' : 'false'}
                    />
                    {fieldErrors.username ? (
                      <p className="text-sm text-red-600">{fieldErrors.username}</p>
                    ) : null}
                  </div>
                  <div className="space-y-2">
                    <label className="text-sm font-medium" htmlFor="password">{t('login.password')}</label>
                    <div className="relative">
                      <Input
                        id="password"
                        type="password"
                        autoComplete="current-password"
                        value={password}
                        onChange={(event) => {
                          setPassword(event.target.value)
                          if (fieldErrors.password) {
                            setFieldErrors((current) => ({ ...current, password: undefined }))
                          }
                        }}
                        placeholder={t('login.passwordPlaceholder')}
                        aria-invalid={fieldErrors.password ? 'true' : 'false'}
                      />
                    </div>
                    {fieldErrors.password ? (
                      <p className="text-sm text-red-600">{fieldErrors.password}</p>
                    ) : null}
                  </div>
                  {loginMutation.error ? (
                    <p className="text-sm text-red-600">{loginMutation.error.message}</p>
                  ) : null}
                  <Button className="w-full" variant="secondary" disabled={loginMutation.isPending} type="submit">
                    {loginMutation.isPending ? t('login.submitting') : t('login.submit')}
                  </Button>
                  <p className="text-center text-xs">
                    <Link to="/reset-password" className="font-medium text-muted-foreground hover:text-foreground hover:underline">
                      {t('login.forgotPassword')}
                    </Link>
                  </p>
                </form>
              )}
            </div>
          </div>
        </div>

        <p className="text-center text-xs text-muted-foreground">
          {t('login.agreementPrefix')}
          {isChinese ? null : ' '}
          <Link to="/terms" className="text-primary hover:underline">
            {t('login.terms')}
          </Link>
          {isChinese ? null : ' '}
          {t('login.and')}
          {isChinese ? null : ' '}
          <Link to="/privacy" className="text-primary hover:underline">
            {t('login.privacy')}
          </Link>
        </p>
      </div>
    </div>
  )
}
