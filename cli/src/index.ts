#!/usr/bin/env node
import { cac } from 'cac'
import { doctorCommand } from './commands/doctor'
import { helpCommand } from './commands/help'
import { installCommand, type InstallCommandOptions } from './commands/install'
import { listCommand, type ListCommandOptions } from './commands/list'
import { loginCommand } from './commands/login'
import { logoutCommand } from './commands/logout'
import { publishCommand, type PublishCommandOptions } from './commands/publish'
import { removeCommand, type RemoveCommandOptions } from './commands/remove'
import { searchCommand } from './commands/search'
import { selfUpdateCommand } from './commands/self-update'
import { versionCommand } from './commands/version'
import { whoamiCommand } from './commands/whoami'
import { inspectCommand, namespacesCommand, moveCommand, moveStatusCommand, withdrawCommand, type ManagementOptions } from './commands/manage'
import { CliError } from './shared/errors'
import { renderError } from './shared/output'

const cli = cac('skill-center')
cli.option('--registry <url>', 'Skill Center server URL')
cli.option('--token <token>', 'API token (prefer SKILL_CENTER_TOKEN)')
cli.option('--json', 'Output JSON')

function toArray(value: string | string[] | undefined): string[] | undefined {
  return value === undefined ? undefined : Array.isArray(value) ? value : [value]
}

cli.command('', 'Show help').action(() => helpCommand([]))
cli.command('help [command]', 'Show help').action((topic: string | undefined, options: ManagementOptions) =>
  helpCommand([...(topic ? [topic] : []), ...(options.json ? ['--json'] : [])]))
cli.command('version', 'Show CLI version').action((options: ManagementOptions) => versionCommand(options.json ? ['--json'] : []))
cli.command('login', 'Login using an API token or browser')
  .option('--browser', 'Authorize in a browser')
  .action(loginCommand)
cli.command('logout', 'Remove the current server token').action(logoutCommand)
cli.command('whoami', 'Verify current identity').action(whoamiCommand)
cli.command('search [query]', 'Search installable skills')
  .option('--limit <n>', 'Max results', { default: 20 })
  .action((query: string | undefined, options: ManagementOptions & { limit?: number }) => searchCommand(query ?? '', options))
cli.command('inspect <skill>', 'Show skill details and its stable ID')
  .action(inspectCommand)
cli.command('namespaces', 'List visible namespaces')
  .option('--page <n>', 'Page number (zero-based)', { default: 0 })
  .option('--size <n>', 'Page size', { default: 20 })
  .action(namespacesCommand)
cli.command('install <slug>', 'Install a skill locally')
  .option('--namespace <slug>', 'Override namespace')
  .option('--version <v>', 'Exact skill version')
  .option('--scope <scope>', 'user or project')
  .option('--agent <profile>', 'Agent profile (repeatable)')
  .option('--dir <path>', 'Custom skills directory')
  .option('--force', 'Replace an existing installation')
  .action((slug: string, options: Omit<InstallCommandOptions, 'agent'> & { agent?: string | string[] }) =>
    installCommand(slug, { ...options, agent: toArray(options.agent) }))
cli.command('list', 'List local installations')
  .option('--agent <profile>', 'Filter by agent (repeatable)')
  .option('--dir <path>', 'Filter by skills directory')
  .action((options: Omit<ListCommandOptions, 'agent'> & { agent?: string | string[] }) =>
    listCommand({ ...options, agent: toArray(options.agent) }))
cli.command('remove <slug>', 'Remove a local or remote skill')
  .option('--agent <profile>', 'Filter by agent (repeatable)')
  .option('--all', 'Remove all local targets')
  .option('--remote', 'Delete remote skill')
  .option('--hard', 'Confirm remote deletion without prompting')
  .option('--namespace <slug>', 'Namespace')
  .action((slug: string, options: Omit<RemoveCommandOptions, 'agent'> & { agent?: string | string[] }) =>
    removeCommand(slug, { ...options, agent: toArray(options.agent) }))
cli.command('doctor', 'Rebuild local inventory from installed metadata').action(doctorCommand)
cli.command('publish <path>', 'Upload a new skill package')
  .option('--namespace <slug>', 'Target namespace')
  .option('--visibility <v>', 'public, namespace-only, or private')
  .option('--dry-run', 'Validate without publishing')
  .option('--confirm-warnings', 'Accept package warnings')
  .action(publishCommand)
cli.command('update <skill-id> <path>', 'Upload a version, preserving namespace and visibility')
  .option('--confirm-warnings', 'Accept package warnings')
  .action((id: string, path: string, options: PublishCommandOptions) => publishCommand(path, { ...options, skillId: id }))
cli.command('move <skill-id> <namespace>', 'Move a skill using its latest usable version')
  .option('--dry-run', 'Only check eligibility')
  .option('--confirm-warnings', 'Accept precheck warnings')
  .option('--idempotency-key <key>', 'Reuse this key when retrying a submission')
  .action(moveCommand)
cli.command('move-status <skill-id>', 'Show targets, latest usable version, and current move request')
  .action(moveStatusCommand)
cli.command('withdraw <skill-id> <request-id>', 'Withdraw an active move request')
  .action(withdrawCommand)
cli.command('self-update', 'Check or update this CLI npm package')
  .option('--check', 'Check without installing')
  .option('--npm-registry <url>', 'npm registry (separate from the Skill Center server)')
  .action(selfUpdateCommand)
cli.help()

try {
  if (process.argv.length === 3 && process.argv[2] === '--version') process.argv[2] = 'version'
  const parsed = cli.parse(process.argv, { run: false })
  if (cli.matchedCommand?.name === '' && parsed.args.length) {
    throw new CliError(`unknown command "${parsed.args[0]}"; run skill-center help`, 5)
  }
  const output: unknown = await cli.runMatchedCommand()
  if (typeof output === 'string' && output) process.stdout.write(`${output}\n`)
} catch (error) {
  const failure = error instanceof Error && error.name === 'CACError'
    ? new CliError(error.message, 5)
    : error
  process.stderr.write(`${renderError(failure, process.argv.includes('--json'))}\n`)
  process.exitCode = failure instanceof CliError ? failure.exitCode : 1
}
