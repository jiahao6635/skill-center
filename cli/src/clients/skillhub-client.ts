import type { components } from '../../../web/src/api/generated/schema'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

type Schemas = components['schemas']
export type PublishResponse = Schemas['PublishResponse']
export type SharingSettings = Schemas['SkillSharingSettingsResponse']
export type MoveRequest = Schemas['SkillShareCommand']

export interface WhoAmIResponse {
  handle: string
  displayName: string
  email?: string
}

export interface SearchItem {
  namespace: string
  slug: string
  latestVersion: string
  summary: string
}

export interface SearchResponse {
  items: SearchItem[]
  total: number
  limit: number
}

export interface ResolveResponse {
  namespace: string
  slug: string
  version: string
  versionId: number
  fingerprint: string
  downloadUrl: string
}

export interface DeleteResponse {
  ok: boolean
  scope: string
  action: string
  namespace: string
  slug: string
}

export interface DryRunResponse {
  valid: boolean
  errors: string[]
  warnings: string[]
  resolvedSlug: string | null
  resolvedVersion: string | null
}

/** CLI and native API routes share authentication, timeouts, and error handling. */
export class SkillHubClient {
  constructor(
    readonly registry: string,
    readonly token?: string,
    private readonly fetchImpl: typeof fetch = fetch
  ) {}

  async whoami(): Promise<WhoAmIResponse> {
    return this.json('/api/cli/v1/auth/whoami')
  }

  async search(query: string, limit: number): Promise<SearchResponse> {
    const params = new URLSearchParams({ q: query, limit: String(limit) })
    return this.json(`/api/cli/v1/skills/search?${params}`)
  }

  async resolve(namespace: string, slug: string, version?: string): Promise<ResolveResponse> {
    const params = version ? `?version=${encodeURIComponent(version)}` : ''
    return this.json(`/api/cli/v1/skills/${segment(namespace)}/${segment(slug)}/resolve${params}`)
  }

  async downloadUrl(namespace: string, slug: string, version?: string): Promise<string> {
    const suffix = version ? `/versions/${segment(version)}` : ''
    return `${this.registry}/api/cli/v1/skills/${segment(namespace)}/${segment(slug)}${suffix}/download`
  }

  async download(namespace: string, slug: string, version?: string): Promise<Response> {
    const url = await this.downloadUrl(namespace, slug, version)
    const response = await this.request(url.slice(this.registry.length), {}, true)
    if (response.status === 401 || response.status === 403) {
      throw new CliError('authentication failed', EXIT.auth)
    }
    if (response.status === 404) throw new CliError('skill or version not found', EXIT.generic)
    if (!response.ok) throw new CliError(`download failed with status ${response.status}`, EXIT.generic)
    return response
  }

  async deleteRemote(namespace: string, slug: string): Promise<DeleteResponse> {
    return this.json(`/api/cli/v1/skills/${segment(namespace)}/${segment(slug)}`, { method: 'DELETE' })
  }

  async publish(namespace: string, file: Blob, visibility: string, fileName = 'skill.zip', confirmWarnings = false): Promise<PublishResponse> {
    const body = packageForm(file, fileName, confirmWarnings)
    body.set('visibility', visibility)
    return this.json(`/api/v1/skills/${segment(namespace)}/publish`, { method: 'POST', body })
  }

  async updateVersion(skillId: string, file: Blob, fileName: string, confirmWarnings = false): Promise<PublishResponse> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/versions`, {
      method: 'POST', body: packageForm(file, fileName, confirmWarnings)
    })
  }

  async validatePublish(namespace: string, file: Blob, visibility: string, fileName = 'skill.zip'): Promise<DryRunResponse> {
    const body = packageForm(file, fileName, false)
    body.set('visibility', visibility)
    return this.json(`/api/cli/v1/skills/${segment(namespace)}/publish/validate`, { method: 'POST', body })
  }

  async inspect(namespace: string, slug: string): Promise<Schemas['SkillDetailResponse']> {
    return this.json(`/api/v1/skills/${segment(namespace)}/${segment(slug)}`)
  }

  async location(skillId: string): Promise<Schemas['SkillLocationResponse']> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/location`)
  }

  async namespaces(page: number, size: number): Promise<Schemas['PageResponseNamespaceResponse']> {
    return this.json(`/api/v1/namespaces?page=${page}&size=${size}`)
  }

  async sharing(skillId: string): Promise<SharingSettings> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/sharing`)
  }

  async precheckMove(skillId: string, command: MoveRequest): Promise<Schemas['SkillSharePrecheckResponse']> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/sharing/precheck`, jsonPost(command))
  }

  async move(skillId: string, command: MoveRequest): Promise<Schemas['SkillShareResponse']> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/sharing`, jsonPost(command))
  }

  async withdraw(skillId: string, requestId: string): Promise<Schemas['SkillShareResponse']> {
    return this.json(`/api/v1/skills/by-id/${segment(skillId)}/sharing/${segment(requestId)}/withdraw`, { method: 'POST' })
  }

  private async request(path: string, init: RequestInit = {}, download = false): Promise<Response> {
    const headers = new Headers(init.headers)
    if (this.token) headers.set('Authorization', `Bearer ${this.token}`)
    try {
      return await this.fetchImpl(`${this.registry}${path}`, {
        ...init, headers, signal: AbortSignal.timeout(120_000),
        // JSON redirects often indicate a login page. Downloads may redirect to object storage;
        // fetch strips Authorization on cross-origin redirects.
        redirect: download ? 'follow' : 'error'
      })
    } catch {
      throw new CliError('registry unreachable', EXIT.network, { registry: this.registry, next: 'check network or pass --registry' })
    }
  }

  private async json<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await this.request(path, init)
    const body: unknown = await response.json().catch(() => null)
    const envelope = body && typeof body === 'object' ? body as Record<string, unknown> : null
    if (response.status === 401) {
      throw new CliError('authentication failed', EXIT.auth, { registry: this.registry, next: 'run skill-center login --browser' })
    }
    if (response.status === 403) {
      throw new CliError('access denied — token may lack required scope', EXIT.auth, { next: 'check namespace permissions and token scopes' })
    }
    if (!response.ok || (envelope?.code !== undefined && envelope.code !== 0)) {
      const message = typeof envelope?.msg === 'string' ? envelope.msg : `registry returned ${response.status}`
      throw new CliError(message, response.status === 502 || response.status === 503 ? EXIT.network : EXIT.generic, {
        registry: this.registry, status: response.status, requestId: envelope?.requestId
      })
    }
    if (!envelope || !('data' in envelope) || envelope.data == null) {
      throw new CliError('invalid registry response', EXIT.network, { registry: this.registry })
    }
    return envelope.data as T
  }
}

function segment(value: string): string {
  if (!value || value === '.' || value === '..' || /[\\/]/.test(value)) {
    throw new CliError('invalid resource identifier', EXIT.usage)
  }
  return encodeURIComponent(value)
}

function packageForm(file: Blob, fileName: string, confirmWarnings: boolean): FormData {
  const form = new FormData()
  form.set('file', file, fileName)
  form.set('confirmWarnings', String(confirmWarnings))
  return form
}

function jsonPost(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}
