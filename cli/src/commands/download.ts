import { writeFile } from 'node:fs/promises'
import { constants } from 'node:fs'
import { SkillHubClient } from '../clients/skillhub-client'
import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { parseSkillName } from '../shared/skill-name-parser'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

export interface DownloadCommandOptions {
  version?: string
  output?: string
  overwrite?: boolean
  registry?: string
  token?: string
  json?: boolean
}

export async function downloadCommand(coordinate: string, options: DownloadCommandOptions): Promise<string> {
  if (!options.output) throw new CliError('--output is required', EXIT.usage)
  const { namespace, slug } = parseSkillName(coordinate)
  const registry = resolveRegistry(options, process.env, await new ConfigStore().read())
  const token = resolveToken(options, process.env, await new CredentialsStore().getToken(registry))
  const response = await new SkillHubClient(registry, token).download(namespace, slug, options.version)
  await writeResponse(options.output, response, Boolean(options.overwrite))
  const result = {
    ok: true,
    schemaVersion: 1,
    contractVersion: 1,
    command: 'download',
    namespace,
    slug,
    version: options.version ?? null,
    output: options.output,
  }
  return options.json
    ? JSON.stringify(result)
    : `Downloaded @${namespace}/${slug}${options.version ? `@${options.version}` : ''} to ${options.output}`
}

export async function writeResponse(path: string, response: Response, overwrite: boolean): Promise<void> {
  try {
    const bytes = new Uint8Array(await response.arrayBuffer())
    await writeFile(path, bytes, { flag: overwrite ? 'w' : constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY })
  } catch (error) {
    const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : ''
    if (code === 'EEXIST') throw new CliError(`output already exists: ${path}`, EXIT.conflict)
    throw new CliError(`failed to write output: ${path}`, EXIT.filesystem)
  }
}
