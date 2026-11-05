import { DEFAULT_REGISTRY } from '../shared/constants'
import { CliError } from '../shared/errors'

export function resolveRegistry(
  args: { registry?: string | undefined },
  env: NodeJS.ProcessEnv,
  config: { registry?: string | undefined }
): string {
  return normalizeRegistry(args.registry || env.SKILL_CENTER_REGISTRY || env.SKILLHUB_REGISTRY || config.registry || DEFAULT_REGISTRY)
}

export function resolveToken(
  args: { token?: string | undefined },
  env: NodeJS.ProcessEnv,
  storedToken?: string | undefined
): string | undefined {
  return args.token || env.SKILL_CENTER_TOKEN || env.SKILLHUB_TOKEN || storedToken
}

function normalizeRegistry(registry: string): string {
  try {
    const url = new URL(registry)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error()
    return url.toString().replace(/\/+$/, '')
  } catch {
    throw new CliError('registry must be an HTTP(S) URL without credentials, query, or fragment', 5)
  }
}
