/**
 * Deep link utilities for launching QoderWork desktop app to install skills.
 *
 * URL format:
 *   Web:    https://{domain}/link/skill/install?name={slug}&config={base64(JSON)}
 *   Protocol: qoder-work://skill/install?name={slug}&config={base64(JSON)}
 */

export interface SkillInstallConfig {
  scope: string
  version?: string
  skill_name?: string
  skill_name_zh?: string
  description?: string
  description_zh?: string
  download_url: string
  source?: string
}

const SKILL_NAME_PATTERN = /^[a-z0-9\-_]+$/
const PROTOCOL_SCHEME = 'qoder-work'
const LAUNCH_TIMEOUT_MS = 300_000

export { LAUNCH_TIMEOUT_MS }

/**
 * Decode a URL-safe base64-encoded config parameter.
 *
 * Handles:
 * - Spaces that replaced `+` during URL transport → restored to `+`
 * - Missing padding (`=`) → re-padded
 * - Standard and URL-safe base64 variants
 *
 * Returns null if decoding or JSON parsing fails.
 */
export function decodeConfigParam(raw: string): SkillInstallConfig | null {
  try {
    // Restore `+` from spaces (URL transport may replace `+` with space)
    let base64 = raw.replace(/ /g, '+')
    // URL-safe variant: replace `-` → `+`, `_` → `/`
    base64 = base64.replace(/-/g, '+').replace(/_/g, '/')
    // Re-pad to multiple of 4
    const padNeeded = (4 - (base64.length % 4)) % 4
    base64 += '='.repeat(padNeeded)

    const binary = atob(base64)
    // Decode UTF-8 bytes back to string
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i)
    }
    const json = new TextDecoder().decode(bytes)
    const parsed: unknown = JSON.parse(json)

    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null
    }
    return parsed as SkillInstallConfig
  } catch {
    return null
  }
}

/**
 * Encode a SkillInstallConfig to base64.
 *
 * Handles Unicode characters (Chinese, emojis, etc.) by converting to UTF-8
 * bytes before base64 encoding, since btoa() only supports Latin1.
 */
export function encodeConfigParam(config: SkillInstallConfig): string {
  const json = JSON.stringify(config)
  const bytes = new TextEncoder().encode(json)
  const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join('')
  return btoa(binary)
}

/**
 * Build the QoderWork protocol URL for skill installation.
 *
 * Format: qoder-work://skill/install?name={name}&config={config}
 */
export function buildProtocolUrl(name: string, config: string): string {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('config', config)
  return `${PROTOCOL_SCHEME}://skill/install?${params.toString()}`
}

/**
 * Build the web intermediate page URL that will trigger the protocol launch.
 *
 * Format: {baseUrl}/link/skill/install?name={name}&config={config}
 */
export function buildDeepLinkUrl(baseUrl: string, name: string, config: string): string {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('config', config)
  const trimmedBase = baseUrl.replace(/\/+$/, '')
  return `${trimmedBase}/link/skill/install?${params.toString()}`
}

/**
 * Validate skill name format: lowercase alphanumeric, hyphens, underscores only.
 */
export function validateSkillName(name: string): boolean {
  return SKILL_NAME_PATTERN.test(name)
}

/**
 * Validate required config fields.
 * Returns an array of missing field names (empty if valid).
 */
export function validateConfig(config: SkillInstallConfig): string[] {
  const missing: string[] = []
  if (!config.scope) missing.push('scope')
  if (!config.download_url) missing.push('download_url')
  return missing
}
