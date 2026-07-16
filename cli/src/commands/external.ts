import { randomUUID } from 'node:crypto'
import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'
import { SkillHubClient } from '../clients/skillhub-client'

export interface ExternalOptions { version?: string; namespace?: string; visibility?: string; packageSha256?: string; warningDigest?: string; confirmWarnings?: boolean; confirmMissingLicense?: boolean; registry?: string; token?: string; json?: boolean; page?: number; limit?: number; sort?: string; idempotencyKey?: string }

export async function externalCommand(action: string, value: string, options: ExternalOptions): Promise<string> {
  const registry = resolveRegistry(options, process.env, await new ConfigStore().read())
  const token = resolveToken(options, process.env, await new CredentialsStore().getToken(registry))
  const requestId = options.idempotencyKey ?? randomUUID()
  const api = new SkillHubClient(registry, token)
  let data: unknown
  if (action === 'search') {
    const query = new URLSearchParams({ q: value, page: String(options.page ?? 0), size: String(options.limit ?? 20), sort: options.sort ?? 'relevance' })
    data = await api.requestJson(`/external/search?${query}`)
  } else if (action === 'show') data = await api.requestJson(`/external/${encodeURIComponent(value)}`)
  else if (action === 'import') {
    if (!token) throw new CliError('authentication required', EXIT.auth)
    if (!options.version || !options.namespace) throw new CliError('--version and --namespace are required', EXIT.usage)
    const base = `/external/${encodeURIComponent(value)}/versions/${encodeURIComponent(options.version)}/imports`
    const target = { namespace: options.namespace, visibility: options.visibility ?? 'PRIVATE' }
    const validation = await api.requestJson<{
      valid: boolean; packageSha256: string; warningDigest: string; errors: string[]; warnings: string[]
      licenseStatus: string
    }>(`${base}/validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(target),
    })
    if (!validation.valid) {
      throw new CliError('external package validation failed', EXIT.validation, {
        code: 'PACKAGE_VALIDATION_FAILED', requestId, errors: validation.errors,
      })
    }
    if (options.packageSha256 && options.packageSha256 !== validation.packageSha256) {
      throw new CliError('validated package SHA does not match --package-sha256', EXIT.conflict, {
        code: 'UPSTREAM_CONTENT_CHANGED', requestId, packageSha256: validation.packageSha256,
      })
    }
    if (validation.warnings.length > 0
        && (!options.confirmWarnings || options.warningDigest !== validation.warningDigest)) {
      throw new CliError('external import warnings require explicit digest confirmation', EXIT.confirmation, {
        code: 'CONFIRMATION_REQUIRED', requestId, warningDigest: validation.warningDigest,
        warnings: validation.warnings, packageSha256: validation.packageSha256,
      })
    }
    if (validation.licenseStatus === 'MISSING' && !options.confirmMissingLicense) {
      throw new CliError('missing license requires an explicit per-package confirmation', EXIT.confirmation, {
        code: 'LICENSE_CONFIRMATION_REQUIRED', requestId, packageSha256: validation.packageSha256,
      })
    }
    data = await api.requestJson(base, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
      body: JSON.stringify({
        ...target,
        packageSha256: validation.packageSha256,
        warningDigest: validation.warningDigest,
        confirmWarnings: validation.warnings.length > 0,
        confirmMissingLicense: Boolean(options.confirmMissingLicense),
      }),
    })
  } else throw new CliError(`unsupported external action: ${action}`, EXIT.usage)
  return JSON.stringify({ ok: true, schemaVersion: 1, contractVersion: 1, command: `external.${action}`, requestId, data })
}
