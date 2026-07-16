import { ConfigStore } from '../stores/config-store'
import { resolveRegistry } from '../services/registry-service'
import { removeLocalSkill } from '../services/remove-service'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'
import { parseSkillName } from '../shared/skill-name-parser'

export interface RemoveCommandOptions {
  agent?: string[] | undefined
  all?: boolean | undefined
  remote?: boolean | undefined
  hard?: boolean | undefined
  namespace?: string | undefined
  registry?: string | undefined
  token?: string | undefined
  json?: boolean | undefined
}

export async function removeCommand(skillNameArg: string, options: RemoveCommandOptions): Promise<string> {
  if (options.all && options.agent?.length) {
    throw new CliError('--all cannot be used with --agent', EXIT.usage)
  }
  if (options.remote && (options.agent?.length || options.all)) {
    throw new CliError('--remote cannot be used with --agent or --all', EXIT.usage)
  }
  if (options.remote) throw new CliError('remote whole-skill deletion is not supported', EXIT.usage)

  const configStore = new ConfigStore()
  const registry = resolveRegistry(options, process.env, await configStore.read())

  const parsed = parseSkillName(skillNameArg)
  const slug = parsed.slug

  // Local remove
  const result = await removeLocalSkill({
    registry, slug,
    agents: options.agent,
    all: options.all
  })

  if (options.json) {
    return JSON.stringify({ ok: true, scope: 'local', removed: result.removed })
  }
  return result.removed.map(r =>
    r.existed
      ? `Removed ${r.namespace}/${slug} from ${r.dir} (${r.agent})`
      : `Cleaned stale record for ${r.namespace}/${slug} at ${r.dir} (${r.agent}, directory already missing)`
  ).join('\n')
}
