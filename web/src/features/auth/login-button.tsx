import { useAuthMethods } from './use-auth-methods.ts'

interface LoginButtonProps {
  returnTo?: string
}

/**
 * Renders a single OAuth provider as a circular icon with label below,
 * matching the "其他登录方式" style shown in the reference design.
 */
function OAuthProviderItem({ provider, displayName, actionUrl }: { provider: string, displayName: string, actionUrl: string }) {
  const normalizedProvider = provider.toLowerCase()
  return (
    <button
      type="button"
      className="flex flex-col items-center gap-2 group cursor-pointer"
      onClick={() => { window.location.href = actionUrl }}
    >
      <div className="w-12 h-12 rounded-full border border-border flex items-center justify-center transition-colors group-hover:border-primary/50 group-hover:bg-primary/5">
        <img
          src={`/${normalizedProvider}-logo.png`}
          alt={provider}
          className="w-6 h-6 object-contain"
        />
      </div>
      <span className="text-sm text-muted-foreground group-hover:text-foreground transition-colors">
        {displayName}
      </span>
    </button>
  )
}

/**
 * Renders OAuth login providers as circular icon buttons with labels,
 * styled like the "其他登录方式" section in the reference design.
 */
export function LoginButton({ returnTo }: LoginButtonProps) {
  const { data, isLoading } = useAuthMethods(returnTo)

  const providers = (data ?? []).filter((method) => method.methodType === 'OAUTH_REDIRECT')

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-4">
        <div className="w-12 h-12 rounded-full border border-border flex items-center justify-center animate-shimmer" />
      </div>
    )
  }

  return (
    <div className="flex items-center justify-center gap-8">
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

