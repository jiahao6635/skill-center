import { readFile, writeFile, unlink } from 'node:fs/promises'
import { dirname } from 'node:path'
import { applyCredentialPermissions, ensureDir, joinPath, pathExists, userStateDir } from '../platform/paths'

interface DeviceFlow { registry: string; deviceCode: string; expiresAt: string }
export class DeviceFlowStore {
  readonly path: string
  constructor(home?: string) { this.path = joinPath(userStateDir(home), 'device-flows.json') }
  async set(id: string, flow: DeviceFlow) { const all = await this.read(); await ensureDir(dirname(this.path)); await writeFile(this.path, JSON.stringify({ ...all, [id]: flow })); await applyCredentialPermissions(this.path) }
  async get(id: string) { return (await this.read())[id] }
  async delete(id: string) { const all = await this.read(); delete all[id]; if (Object.keys(all).length === 0) { if (await pathExists(this.path)) await unlink(this.path); return } await writeFile(this.path, JSON.stringify(all)); await applyCredentialPermissions(this.path) }
  private async read(): Promise<Record<string, DeviceFlow>> { if (!(await pathExists(this.path))) return {}; return JSON.parse(await readFile(this.path, 'utf8')) as Record<string, DeviceFlow> }
}
