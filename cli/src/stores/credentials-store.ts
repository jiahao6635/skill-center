import { readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { joinPath, userStateDir, ensureDir, applyCredentialPermissions, pathExists } from '../platform/paths'
import { SystemCredentialStore } from './system-credential-store'

interface CredentialsFile {
  tokens: Record<string, string>
}

export class CredentialsStore {
  readonly path: string
  private readonly systemStore: SystemCredentialStore

  constructor(home?: string, systemStore = new SystemCredentialStore()) {
    this.path = joinPath(userStateDir(home), 'credentials.json')
    this.systemStore = systemStore
  }

  async read(): Promise<CredentialsFile> {
    if (!(await pathExists(this.path))) return { tokens: {} }
    return JSON.parse(await readFile(this.path, 'utf-8')) as CredentialsFile
  }

  async getToken(registry: string): Promise<string | undefined> {
    const systemToken = await this.systemStore.get(registry)
    if (systemToken) return systemToken
    return (await this.read()).tokens[registry]
  }

  async setToken(registry: string, token: string): Promise<'system' | 'file'> {
    if (await this.systemStore.set(registry, token)) {
      await this.deleteFileToken(registry)
      return 'system'
    }
    const current = await this.read()
    await ensureDir(dirname(this.path))
    await writeFile(this.path, JSON.stringify({ tokens: { ...current.tokens, [registry]: token } }, null, 2))
    await applyCredentialPermissions(this.path)
    return 'file'
  }

  async deleteToken(registry: string): Promise<void> {
    await this.systemStore.delete(registry)
    await this.deleteFileToken(registry)
  }

  private async deleteFileToken(registry: string): Promise<void> {
    const current = await this.read()
    const tokens = { ...current.tokens }
    delete tokens[registry]
    await ensureDir(dirname(this.path))
    await writeFile(this.path, JSON.stringify({ tokens }, null, 2))
    await applyCredentialPermissions(this.path)
  }
}
