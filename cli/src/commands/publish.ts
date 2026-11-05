import { stat, readFile } from 'node:fs/promises'
import { basename } from 'node:path'
import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { SkillHubClient } from '../clients/skillhub-client'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'
import { createZip, isZipFile } from '../platform/archive'
import { requireId } from './manage'

export interface PublishCommandOptions {
  namespace?: string
  visibility?: string
  registry?: string
  token?: string
  json?: boolean
  dryRun?: boolean
  skillId?: string
  confirmWarnings?: boolean
}

export async function publishCommand(path: string, options: PublishCommandOptions): Promise<string> {
  if (options.skillId) {
    requireId(options.skillId)
    if (options.namespace || options.visibility || options.dryRun) {
      throw new CliError('update preserves namespace and visibility; use move to change namespace', EXIT.usage)
    }
  }
  // Validate local path
  let pathStat
  try {
    pathStat = await stat(path)
  } catch {
    throw new CliError(`path not found: ${path}`, EXIT.filesystem, { path })
  }

  const configStore = new ConfigStore()
  const credentialsStore = new CredentialsStore()
  const registry = resolveRegistry(options, process.env, await configStore.read())
  const token = resolveToken(options, process.env, await credentialsStore.getToken(registry))
  const namespace = options.namespace ?? 'global'
  const visibility = options.visibility ?? 'public'
  if (!['public', 'namespace-only', 'private'].includes(visibility)) {
    throw new CliError('--visibility must be public, namespace-only, or private', EXIT.usage)
  }

  if (!token) {
    throw new CliError('authentication required for publish', EXIT.auth, { next: 'run `skill-center login`' })
  }

  // Create or read archive
  let archiveBlob: Blob
  let archiveName: string
  if (pathStat.isFile()) {
    if (await isZipFile(path)) {
      const buffer = await readFile(path)
      archiveBlob = new Blob([buffer], { type: 'application/zip' })
      archiveName = basename(path)
    } else {
      throw new CliError(`file must be a zip archive: ${path}`, EXIT.filesystem, { path })
    }
  } else if (pathStat.isDirectory()) {
    archiveBlob = await createZip(path)
    archiveName = `${basename(path)}.zip`
  } else {
    throw new CliError(`path must be a file or directory: ${path}`, EXIT.filesystem, { path })
  }

  const client = new SkillHubClient(registry, token)

  if (options.dryRun) {
    const result = await client.validatePublish(namespace, archiveBlob, toServerVisibility(visibility), archiveName)

    if (options.json) {
      if (!result.valid) {
        process.stdout.write(JSON.stringify(result) + '\n')
        throw new CliError('validation failed', EXIT.validation)
      }
      return JSON.stringify(result)
    }

    const lines: string[] = []
    if (result.valid) {
      lines.push('Validation passed')
    } else {
      lines.push('Validation failed')
    }
    if (result.resolvedSlug) {
      lines.push(`  Slug: ${result.resolvedSlug}`)
    }
    if (result.resolvedVersion) {
      lines.push(`  Version: ${result.resolvedVersion}`)
    }
    if (result.errors.length > 0) {
      lines.push('Errors:')
      for (const error of result.errors) {
        lines.push(`  - ${error}`)
      }
    }
    if (result.warnings.length > 0) {
      lines.push('Warnings:')
      for (const warning of result.warnings) {
        lines.push(`  - ${warning}`)
      }
    }
    if (!result.valid) {
      process.stdout.write(lines.join('\n') + '\n')
      throw new CliError('validation failed', EXIT.validation)
    }
    return lines.join('\n')
  }

  const result = options.skillId
    ? await client.updateVersion(options.skillId, archiveBlob, archiveName, options.confirmWarnings)
    : await client.publish(namespace, archiveBlob, toServerVisibility(visibility), archiveName, options.confirmWarnings)
  if (!Number.isSafeInteger(result.skillId) || !result.namespace || !result.slug || !result.version || !result.status) {
    throw new CliError('invalid publish response; check the skill status before retrying', EXIT.network)
  }

  const detailUrl = `${registry}/skills/by-id/${result.skillId}`

  if (options.json) {
    return JSON.stringify({
      ok: true,
      namespace: result.namespace,
      slug: result.slug,
      version: result.version,
      skillId: result.skillId,
      status: result.status,
      detailUrl
    })
  }
  return `Uploaded: @${result.namespace}/${result.slug}@${result.version}\nStatus: ${result.status}\nSkill ID: ${result.skillId}\nDetail: ${detailUrl}`
}

/**
 * Convert kebab-case visibility to UPPER_SNAKE_CASE for server enum.
 */
function toServerVisibility(visibility: string): string {
  return visibility.toUpperCase().replace(/-/g, '_')
}
