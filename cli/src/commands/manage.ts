import { randomUUID } from 'node:crypto'
import { SkillHubClient } from '../clients/skillhub-client'
import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { parseSkillName } from '../shared/skill-name-parser'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'
import { writeResponse } from './download'

export interface ManageOptions {
  version?: string; visibility?: string; targetVersion?: string; reason?: string; comment?: string
  confirm?: string; namespace?: string; status?: string; page?: number; limit?: number
  registry?: string; token?: string; json?: boolean; idempotencyKey?: string; output?: string; overwrite?: boolean
}

async function client(options: ManageOptions) {
  const registry = resolveRegistry(options, process.env, await new ConfigStore().read())
  const token = resolveToken(options, process.env, await new CredentialsStore().getToken(registry))
  if (!token) throw new CliError('authentication required', EXIT.auth, { next: 'run `skillhub login`' })
  return { registry, client: new SkillHubClient(registry, token) }
}

function envelope(command: string, requestId: string, data: unknown): string {
  return JSON.stringify({ ok: true, schemaVersion: 1, contractVersion: 1, command, requestId, data })
}

export async function skillManageCommand(action: string, coordinate: string, options: ManageOptions): Promise<string> {
  const { namespace, slug } = parseSkillName(coordinate)
  const requestId = options.idempotencyKey ?? randomUUID()
  const target = `@${namespace}/${slug}${options.version ? `@${options.version}` : ''}`
  const { client: api } = await client(options)
  if (action === 'show') {
    const data = await api.requestJson(`/manage/skills/${namespace}/${encodeURIComponent(slug)}`)
    return envelope('skill.show', requestId, data)
  }
  if (action === 'versions') {
    const query = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.limit ?? 20) })
    const data = await api.requestJson(`/manage/skills/${namespace}/${encodeURIComponent(slug)}/versions?${query}`)
    return envelope('skill.versions', requestId, data)
  }
  if (action === 'delete-version' && !options.version)
    throw new CliError('--version is required', EXIT.usage)
  if (action === 'delete-version' && options.confirm !== target)
    throw new CliError(`confirmation must exactly match ${target}`, EXIT.confirmation, { code: 'CONFIRMATION_REQUIRED', target })
  const base = `/manage/skills/${namespace}/${encodeURIComponent(slug)}`
  if (action === 'delete-version') {
    const data = await api.requestJson(`${base}/versions/${encodeURIComponent(options.version ?? '')}`, { method: 'DELETE', headers: { 'Idempotency-Key': requestId } })
    return envelope('skill.delete-version', requestId, data)
  }
  if (action === 'rerelease') {
    if (!options.version || !options.targetVersion) throw new CliError('--version and --target-version are required', EXIT.usage)
    const data = await api.requestJson(`${base}/versions/${encodeURIComponent(options.version)}/rerelease`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId }, body: JSON.stringify({ targetVersion: options.targetVersion, confirmWarnings: false }) })
    return envelope('skill.rerelease', requestId, data)
  }
  const paths: Record<string, { path: string; body?: unknown }> = {
    archive: { path: `${base}/archive`, body: options.reason ? { reason: options.reason } : {} },
    unarchive: { path: `${base}/unarchive`, body: {} },
    'submit-review': { path: `${base}/submit-review`, body: { version: options.version, targetVisibility: (options.visibility ?? 'PUBLIC').toUpperCase().replace('-', '_') } },
    'withdraw-review': { path: `${base}/versions/${encodeURIComponent(options.version ?? '')}/withdraw-review`, body: {} },
    'confirm-publish': { path: `${base}/confirm-publish`, body: { version: options.version } }
  }
  const mutation = paths[action]
  if (!mutation) throw new CliError(`unsupported skill action: ${action}`, EXIT.usage)
  if (['submit-review', 'withdraw-review', 'confirm-publish'].includes(action) && !options.version)
    throw new CliError('--version is required', EXIT.usage)
  const data = await api.requestJson(mutation.path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId }, body: JSON.stringify(mutation.body) })
  return envelope(`skill.${action}`, requestId, data)
}

export async function reviewCommand(action: string, reviewId: string | undefined, options: ManageOptions): Promise<string> {
  const requestId = options.idempotencyKey ?? randomUUID()
  const { client: api } = await client(options)
  if (action === 'list') {
    if (!options.namespace) throw new CliError('--namespace is required', EXIT.usage)
    const query = new URLSearchParams({ namespace: options.namespace, status: options.status ?? 'PENDING', page: String(options.page ?? 0), size: String(options.limit ?? 20) })
    return envelope('review.list', requestId, await api.requestJson(`/manage/reviews?${query}`))
  }
  if (action === 'submissions') {
    if (!options.namespace) throw new CliError('--namespace is required', EXIT.usage)
    const query = new URLSearchParams({ namespace: options.namespace, page: String(options.page ?? 0), size: String(options.limit ?? 20) })
    return envelope('review.submissions', requestId, await api.requestJson(`/manage/reviews/submissions?${query}`))
  }
  if (!reviewId) throw new CliError('review id is required', EXIT.usage)
  if (action === 'download') {
    if (!options.output) throw new CliError('--output is required', EXIT.usage)
    const response = await api.requestBinary(`/manage/reviews/${reviewId}/download`)
    await writeResponse(options.output, response, Boolean(options.overwrite))
    return envelope('review.download', requestId, { reviewId, output: options.output })
  }
  const detail = await api.requestJson<Record<string, unknown>>(`/manage/reviews/${reviewId}`)
  if (action === 'show') return envelope('review.show', requestId, detail)
  if (!['approve', 'reject'].includes(action)) throw new CliError(`unsupported review action: ${action}`, EXIT.usage)
  const data = detail as { namespace?: string; skillSlug?: string; skillVersion?: string; taskVersion?: number }
  const target = `@${data.namespace ?? ''}/${data.skillSlug ?? ''}@${data.skillVersion ?? ''}`
  if (options.confirm !== target) throw new CliError(`confirmation must exactly match ${target}`, EXIT.confirmation, { code: 'CONFIRMATION_REQUIRED', target })
  if (action === 'reject' && (!options.comment?.trim() || options.comment.length > 500)) throw new CliError('--comment is required (1-500 characters)', EXIT.usage)
  const result = await api.requestJson(`/manage/reviews/${reviewId}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId }, body: JSON.stringify({ comment: options.comment, version: data.taskVersion }) })
  return envelope(`review.${action}`, requestId, result)
}
