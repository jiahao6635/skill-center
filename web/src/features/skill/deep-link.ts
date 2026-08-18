/**
 * Deep link utilities for launching Qoder / QoderWork to install skills.
 *
 * Web intermediate page:
 *   https://{domain}/link/skill/install?name={slug}&config={base64(JSON)}[&client=qoder]
 *
 * Protocols:
 *   QoderWork: qoder-work://skill/install?name={slug}&config={base64(JSON)}
 *   Qoder:     qoder://aicoding.aicoding-deeplink/chat?text=/install-skill {url}&mode=agent
 */

export type DeepLinkClient = 'qoder-work' | 'qoder'

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
const QODERWORK_PROTOCOL_SCHEME = 'qoder-work'
const QODER_PROTOCOL_PREFIX = 'qoder://aicoding.aicoding-deeplink'
const LAUNCH_TIMEOUT_MS = 300_000

export { LAUNCH_TIMEOUT_MS }

export function parseDeepLinkClient(raw: string | null | undefined): DeepLinkClient {
  return raw === 'qoder' ? 'qoder' : 'qoder-work'
}

export function deepLinkAppName(client: DeepLinkClient): 'Qoder' | 'QoderWork' {
  return client === 'qoder' ? 'Qoder' : 'QoderWork'
}

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
 * Build the Qoder protocol URL that opens Agent chat with /install-skill.
 *
 * Format: qoder://aicoding.aicoding-deeplink/chat?text=/install-skill {url}&mode=agent
 */
export function buildQoderProtocolUrl(downloadUrl: string): string {
  const text = `/install-skill ${downloadUrl}`
  return `${QODER_PROTOCOL_PREFIX}/chat?text=${encodeURIComponent(text)}&mode=agent`
}

/**
 * Build the desktop protocol URL for skill installation.
 *
 * QoderWork: qoder-work://skill/install?name={name}&config={config}
 * Qoder:     qoder://aicoding.aicoding-deeplink/chat?text=/install-skill {url}&mode=agent
 */
export function buildProtocolUrl(
  name: string,
  config: string,
  client: DeepLinkClient = 'qoder-work',
  downloadUrl?: string,
): string {
  if (client === 'qoder') {
    return buildQoderProtocolUrl(downloadUrl ?? '')
  }
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('config', config)
  return `${QODERWORK_PROTOCOL_SCHEME}://skill/install?${params.toString()}`
}

/**
 * Build the web intermediate page URL that will trigger the protocol launch.
 *
 * Format: {baseUrl}/link/skill/install?name={name}&config={config}[&client=qoder]
 */
export function buildDeepLinkUrl(
  baseUrl: string,
  name: string,
  config: string,
  client: DeepLinkClient = 'qoder-work',
): string {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('config', config)
  if (client !== 'qoder-work') {
    params.set('client', client)
  }
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
