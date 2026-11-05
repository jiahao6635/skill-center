import { randomUUID } from 'node:crypto'
import { SkillHubClient } from '../clients/skillhub-client'
import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { parseSkillName } from '../shared/skill-name-parser'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

export interface ManagementOptions {
  registry?: string
  token?: string
  json?: boolean
  page?: number
  size?: number
  dryRun?: boolean
  confirmWarnings?: boolean
  idempotencyKey?: string
}

async function clientFor(options: ManagementOptions, authenticated = false): Promise<SkillHubClient> {
  const registry = resolveRegistry(options, process.env, await new ConfigStore().read())
  const token = resolveToken(options, process.env, await new CredentialsStore().getToken(registry))
  if (authenticated && !token) throw new CliError('authentication required', EXIT.auth, { next: 'run skill-center login --browser' })
  return new SkillHubClient(registry, token)
}

export function requireId(value: string): string {
  if (!/^[1-9]\d*$/.test(value) || BigInt(value) > 9223372036854775807n) {
    throw new CliError('ID must be a positive 64-bit integer', EXIT.usage)
  }
  return value
}

export async function inspectCommand(skill: string, options: ManagementOptions): Promise<string> {
  const client = await clientFor(options)
  const location = /^\d+$/.test(skill)
    ? await client.location(requireId(skill))
    : parseSkillName(skill)
  if (!location.namespace || !location.slug) throw new CliError('invalid skill location', EXIT.network)
  const result = await client.inspect(location.namespace, location.slug)
  return options.json ? JSON.stringify(result) : [
    `@${result.namespace}/${result.slug} (ID: ${result.id})`,
    result.summary,
    `Visibility: ${result.visibility} | Status: ${result.status}`,
    `Published: ${result.publishedVersion?.version ?? '-'}`,
    `Latest: ${result.headlineVersion?.version ?? '-'} (${result.headlineVersion?.status ?? '-'})`
  ].filter(Boolean).join('\n')
}

export async function namespacesCommand(options: ManagementOptions): Promise<string> {
  const page = Number(options.page ?? 0)
  const size = Number(options.size ?? 20)
  if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
    throw new CliError('--page must be >= 0 and --size must be 1–100', EXIT.usage)
  }
  const result = await (await clientFor(options, true)).namespaces(page, size)
  return options.json ? JSON.stringify(result) : [
    ...(result.items ?? []).map(item => `${item.id}\t${item.slug}\t${item.displayName}\t${item.status}`),
    `Page ${result.page ?? page} | Total: ${result.total ?? 0}`
  ].join('\n')
}

export async function moveStatusCommand(skillId: string, options: ManagementOptions): Promise<string> {
  requireId(skillId)
  const result = await (await clientFor(options, true)).sharing(skillId)
  return options.json ? JSON.stringify(result) : [
    `@${result.namespace}/${result.slug} (ID: ${result.skillId})`,
    `Usable version: ${result.versions?.[0]?.version ?? '-'}`,
    `Move: ${result.latestRequest ? `${result.latestRequest.id} ${result.latestRequest.status}` : 'none'}`,
    'Available targets:',
    ...(result.targets ?? []).map(target => `  ${target.id}\t${target.slug}\t${target.displayName}`)
  ].join('\n')
}

export async function moveCommand(skillId: string, namespace: string, options: ManagementOptions): Promise<string> {
  requireId(skillId)
  const key = options.idempotencyKey ?? randomUUID()
  if (!key.trim() || key.length > 64) throw new CliError('--idempotency-key must contain 1–64 characters', EXIT.usage)
  const client = await clientFor(options, true)
  const settings = await client.sharing(skillId)
  const target = settings.targets?.find(item => item.slug === namespace || String(item.id) === namespace)
  if (!target?.id || !Number.isSafeInteger(target.id)) {
    throw new CliError('target namespace is not available for this skill', EXIT.validation, {
      next: `run skill-center move-status ${skillId}`
    })
  }
  const command = { targetNamespaceId: target.id, idempotencyKey: key, confirmWarnings: Boolean(options.confirmWarnings) }
  const precheck = await client.precheckMove(skillId, command)
  if (!precheck.valid) throw new CliError('move precheck failed', EXIT.validation, { ...precheck, idempotencyKey: key })
  if (options.dryRun) return JSON.stringify({ ...precheck, targetNamespace: target.slug, idempotencyKey: key }, null, options.json ? undefined : 2)
  if (precheck.warnings?.length && !options.confirmWarnings) {
    throw new CliError('move requires warning confirmation', EXIT.validation, { warnings: precheck.warnings, next: 'review warnings, then pass --confirm-warnings' })
  }
  try {
    const result = await client.move(skillId, command)
    return options.json ? JSON.stringify({ ...result, idempotencyKey: key })
      : `Move request ${result.id}: ${result.status}\nTarget: ${result.targetNamespace}\nIdempotency key: ${key}\nCheck: skill-center move-status ${skillId}`
  } catch (error) {
    // A lost response may still mean the request was accepted. Keep the retry key visible.
    if (error instanceof CliError) throw new CliError(error.message, error.exitCode, { ...error.details, idempotencyKey: key })
    throw error
  }
}

export async function withdrawCommand(skillId: string, requestId: string, options: ManagementOptions): Promise<string> {
  requireId(skillId)
  requireId(requestId)
  const result = await (await clientFor(options, true)).withdraw(skillId, requestId)
  return options.json ? JSON.stringify(result) : `Move request ${result.id}: ${result.status}`
}
