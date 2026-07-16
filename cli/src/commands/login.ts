import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { AuthService } from '../services/auth-service'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { DeviceFlowStore } from '../stores/device-flow-store'
import { randomUUID } from 'node:crypto'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

export interface LoginCommandOptions {
  registry?: string
  token?: string
  json?: boolean
  namespace?: string
  clientName?: string
  clientId?: string
  resume?: string
  tokenStdin?: boolean
  expiresIn?: string
}

export async function loginCommand(options: LoginCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const credentialsStore = new CredentialsStore()
  const registry = resolveRegistry(options, process.env, await configStore.read())
  if (options.resume) return resumeDeviceFlow(registry, options.resume, options.json === true, configStore, credentialsStore)
  let stdinToken: string | undefined
  if (options.tokenStdin) {
    const chunks: Buffer[] = []
    for await (const chunk of process.stdin) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
    stdinToken = Buffer.concat(chunks).toString('utf8').trim()
  }
  const token = resolveToken({ ...options, token: stdinToken ?? options.token }, process.env, await credentialsStore.getToken(registry))
  if (!token) return startDeviceFlow(registry, options, configStore)
  const result = await new AuthService(configStore, credentialsStore).login(registry, token)
  return options.json
    ? JSON.stringify({ ok: true, registry, handle: result.handle, credentialStore: result.credentialStore,
        ...(result.credentialStore === 'file' ? { warnings: ['System credential store unavailable; token stored in a 0600 file'] } : {}) })
    : `Logged in to ${registry} as ${result.handle}${result.credentialStore === 'file' ? '\nWarning: system credential store unavailable; token stored in a 0600 file.' : ''}`
}

async function startDeviceFlow(registry: string, options: LoginCommandOptions, configStore: ConfigStore): Promise<string> {
  const flowId = randomUUID()
  const expiresInDays = parseExpiresInDays(options.expiresIn)
  const clientId = options.clientId ?? await configStore.getOrCreateClientInstanceId()
  const response = await fetch(`${registry}/api/v1/auth/device/code`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ clientId, clientName: options.clientName ?? 'SkillHub CLI', namespaceSlug: options.namespace, expiresInDays, scopes: ['skill:read', 'skill:publish', 'skill:lifecycle', 'review:read', 'review:decide'] }) })
  if (!response.ok) throw new CliError(`device authorization failed with status ${response.status}`, EXIT.network)
  const envelope = await response.json() as { data: { deviceCode: string; userCode: string; verificationUri: string; expiresIn: number; interval: number } }
  const data = envelope.data
  await new DeviceFlowStore().set(flowId, { registry, deviceCode: data.deviceCode, expiresAt: new Date(Date.now() + data.expiresIn * 1000).toISOString() })
  const verificationUri = data.verificationUri.startsWith('http') ? data.verificationUri : `${registry}${data.verificationUri}`
  const result = { ok: true, schemaVersion: 1, contractVersion: 1, status: 'authorization_required', flowId, userCode: data.userCode, verificationUri, expiresIn: data.expiresIn, interval: data.interval }
  return options.json ? JSON.stringify(result) : `Open ${verificationUri} and enter ${data.userCode}\nThen run: skillhub login --resume ${flowId}`
}

function parseExpiresInDays(value: string | undefined): number {
  if (!value) return 90
  const match = value.trim().match(/^(\d+)(?:d)?$/i)
  const days = match ? Number(match[1]) : Number.NaN
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    throw new CliError('--expires-in must be between 1d and 90d', EXIT.usage)
  }
  return days
}

async function resumeDeviceFlow(registry: string, flowId: string, json: boolean, configStore: ConfigStore, credentialsStore: CredentialsStore): Promise<string> {
  const store = new DeviceFlowStore(); const flow = await store.get(flowId)
  if (!flow || flow.registry !== registry) throw new CliError('device authorization flow not found', EXIT.notFound)
  const response = await fetch(`${registry}/api/v1/auth/device/token`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceCode: flow.deviceCode }) })
  if (!response.ok) throw new CliError(`device authorization failed with status ${response.status}`, EXIT.network)
  const envelope = await response.json() as { data: { accessToken?: string; error?: string } }; const data = envelope.data
  if (data.error) return json ? JSON.stringify({ ok: true, schemaVersion: 1, status: data.error, flowId }) : `Authorization status: ${data.error}`
  if (!data.accessToken) throw new CliError('device authorization returned no token', EXIT.generic)
  const user = await new AuthService(configStore, credentialsStore).login(registry, data.accessToken); await store.delete(flowId)
  return json ? JSON.stringify({ ok: true, schemaVersion: 1, contractVersion: 1, status: 'authenticated', registry, handle: user.handle, credentialStore: user.credentialStore,
    ...(user.credentialStore === 'file' ? { warnings: ['System credential store unavailable; token stored in a 0600 file'] } : {}) })
    : `Logged in to ${registry} as ${user.handle}${user.credentialStore === 'file' ? '\nWarning: system credential store unavailable; token stored in a 0600 file.' : ''}`
}
