import { useAuthMethods } from './use-auth-methods.ts'

interface LoginButtonProps {
  returnTo?: string
}

/**
 * Renders a single OAuth provider as a prominent button with icon and label,
 * designed for primary login display.
 */
function OAuthProviderItem({ provider, displayName, actionUrl }: { provider: string, displayName: string, actionUrl: string }) {
  const normalizedProvider = provider.toLowerCase()
  return (
    <button
      type="button"
      className="flex items-center justify-center gap-3 w-full px-6 py-3 rounded-xl border border-border bg-background hover:bg-muted hover:border-primary/50 transition-all group cursor-pointer"
      onClick={() => { window.location.href = actionUrl }}
    >
      <div className="w-10 h-10 rounded-full flex items-center justify-center">
        <img
          src={`/${normalizedProvider}-logo.png`}
          alt={provider}
          className="w-8 h-8 object-contain"
        />
      </div>
      <span className="text-base font-medium text-foreground group-hover:text-primary transition-colors">
        {displayName}
      </span>
    </button>
  )
}

/**
 * Renders OAuth login providers as prominent buttons,
 * styled as the primary login method.
 */
export function LoginButton({ returnTo }: LoginButtonProps) {
  const { data, isLoading } = useAuthMethods(returnTo)

  const providers = (data ?? []).filter((method) => method.methodType === 'OAUTH_REDIRECT')

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-4">
        <div className="w-full h-14 rounded-xl border border-border animate-shimmer" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      {providers.map((provider) => (
        <OAuthProviderItem
          key={provider.id}
          provider={provider.provider}
          displayName={provider.displayName}
          actionUrl={provider.actionUrl}
        />
      ))}
    </div>
  )
}

