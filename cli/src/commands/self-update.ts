import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { gt, valid } from 'semver'
import { CLI_PACKAGE_NAME, CLI_VERSION, EXIT } from '../shared/constants'
import { CliError } from '../shared/errors'
import { detectInstallMode } from '../platform/package-manager'

interface SelfUpdateOptions {
  check?: boolean
  json?: boolean
  npmRegistry?: string
}

const exec = promisify(execFile)

export async function selfUpdateCommand(options: SelfUpdateOptions): Promise<string> {
  const registry = options.npmRegistry ?? process.env.SKILL_CENTER_NPM_REGISTRY ?? process.env.SKILLHUB_NPM_REGISTRY
  if (registry) {
    try {
      const url = new URL(registry)
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error()
    } catch {
      throw new CliError('invalid npm registry URL', EXIT.usage)
    }
  }
  // npm itself resolves scoped registries and authentication from .npmrc. The business
  // server's bearer token is never used to fetch or install an npm package.
  const env = { ...process.env, ...(registry ? { npm_config_registry: registry } : {}) }
  const runNpm = async (args: string[]): Promise<string> => {
    try {
      const result = await exec('npm', args, { env, timeout: 120_000, shell: process.platform === 'win32' })
      return result.stdout.trim()
    } catch {
      throw new CliError('npm command failed', EXIT.network, { next: 'check npm authentication, registry configuration, and installation permissions' })
    }
  }
  const raw = await runNpm(['view', `${CLI_PACKAGE_NAME}@latest`, 'version', '--json'])
  let latest: unknown
  try { latest = JSON.parse(raw) } catch { /* invalid output handled below */ }
  if (typeof latest !== 'string' || !valid(latest)) throw new CliError('npm returned an invalid version', EXIT.network)
  const available = gt(latest, CLI_VERSION)
  const mode = detectInstallMode()
  const next = `npm install -g ${CLI_PACKAGE_NAME}@${latest}`
  let updated = false
  if (available && !options.check && mode === 'npm-global') {
    await runNpm(['install', '-g', `${CLI_PACKAGE_NAME}@${latest}`])
    updated = true
  }
  const result = { ok: true, currentVersion: CLI_VERSION, latestVersion: latest, available, updated, next: available && !updated ? next : undefined }
  return options.json ? JSON.stringify(result) : updated ? `Updated to ${latest}`
    : available ? `Update available: ${CLI_VERSION} -> ${latest}\n${next}` : `Already up to date (${CLI_VERSION})`
}
