import { CliError } from '../shared/errors'
import { printResult } from '../shared/output'

export const commands = {
  inspect: { summary: 'Show details and stable ID', usage: 'skill-center inspect <@namespace/slug|skill-id>', examples: ['skill-center inspect @global/demo'] },
  namespaces: { summary: 'List visible namespaces', usage: 'skill-center namespaces [--page <n>] [--size <n>]', examples: ['skill-center namespaces --json'] },
  update: { summary: 'Upload a new version by stable ID', usage: 'skill-center update <skill-id> <path> [--confirm-warnings]', examples: ['skill-center update 42 ./my-skill'] },
  move: { summary: 'Move the whole skill to another namespace', usage: 'skill-center move <skill-id> <namespace> [--dry-run] [--confirm-warnings] [--idempotency-key <key>]', examples: ['skill-center move 42 my-team --dry-run'] },
  'move-status': { summary: 'Show move status and eligible targets', usage: 'skill-center move-status <skill-id>', examples: ['skill-center move-status 42'] },
  withdraw: { summary: 'Withdraw an active move request', usage: 'skill-center withdraw <skill-id> <request-id>', examples: ['skill-center withdraw 42 12'] },

  help: {
    summary: 'Show available commands',
    usage: 'skill-center help [command] [--json]',
    examples: ['skill-center help', 'skill-center help install', 'skill-center help --json']
  },
  version: {
    summary: 'Show installed CLI version',
    usage: 'skill-center version [--json]',
    examples: ['skill-center version', 'skill-center version --json']
  },
  login: {
    summary: 'Save registry and token',
    usage: 'skill-center login [--browser] [--token <token>] [--registry <url>] [--json]',
    examples: ['skill-center login --token sk_xxx', 'skill-center login --registry https://skill-center.example.com']
  },
  logout: {
    summary: 'Remove local token',
    usage: 'skill-center logout [--registry <url>] [--json]',
    examples: ['skill-center logout']
  },
  whoami: {
    summary: 'Verify current token',
    usage: 'skill-center whoami [--token <token>] [--registry <url>] [--json]',
    examples: ['skill-center whoami', 'skill-center whoami --json']
  },
  search: {
    summary: 'Search published skills',
    usage: 'skill-center search [query] [--limit <n>] [--registry <url>] [--token <token>] [--json]',
    examples: ['skill-center search', 'skill-center search pdf', 'skill-center search pdf --token sk_xxx']
  },
  install: {
    summary: 'Install a skill locally',
    usage: 'skill-center install <slug> [--scope <user|project>] [--namespace <slug>] [--version <v>] [--agent <profile>] [--dir <path>] [--force] [--json]',
    examples: [
      'skill-center install pdf-parser',
      'skill-center install pdf-parser --scope user',
      'skill-center install pdf-parser --scope project --agent codex'
    ]
  },
  list: {
    summary: 'List local installs',
    usage: 'skill-center list [--agent <profile>] [--dir <path>] [--registry <url>] [--json]',
    examples: ['skill-center list', 'skill-center list --agent codex']
  },
  remove: {
    summary: 'Remove local or remote skill',
    usage: 'skill-center remove <slug> [--agent <profile>] [--all] [--remote] [--hard] [--namespace <slug>] [--json]',
    examples: ['skill-center remove pdf-parser', 'skill-center remove pdf-parser --remote --hard']
  },
  doctor: {
    summary: 'Scan project and merge into local inventory (preserves entries outside scan scope)',
    usage: 'skill-center doctor [--json]',
    examples: ['skill-center doctor', 'skill-center doctor --json']
  },
  publish: {
    summary: 'Publish a local skill package',
    usage: 'skill-center publish <path> [--namespace <slug>] [--visibility <public|namespace-only|private>] [--dry-run] [--confirm-warnings] [--registry <url>] [--json]',
    examples: ['skill-center publish ./my-skill', 'skill-center publish ./my-skill --namespace myspace']
  },
  'self-update': {
    summary: 'Check or update CLI itself',
    usage: 'skill-center self-update [--check] [--json]',
    examples: ['skill-center self-update --check', 'skill-center self-update']
  }
} as const

export function formatCommandList(): string {
  return Object.entries(commands).map(([name, detail]) => `${name.padEnd(10)} ${detail.summary}`).join('\n')
}

export async function helpCommand(args: string[]): Promise<string> {
  const json = args.includes('--json')
  const topic = args.find(arg => !arg.startsWith('--'))
  if (topic && !(topic in commands)) throw new CliError(`unknown command: ${topic}`, 5)
  if (json) {
    if (topic) {
      const detail = commands[topic as keyof typeof commands]
      return printResult({ ok: true, command: topic, ...detail }, true)
    }
    return printResult({
      ok: true,
      commands: Object.entries(commands).map(([name, detail]) => ({ name, description: detail.summary }))
    }, true)
  }
  if (topic) {
    const detail = commands[topic as keyof typeof commands]
    return [
      `${topic} - ${detail.summary}`,
      `Usage: ${detail.usage}`,
      'Examples:',
      ...detail.examples.map(example => `  ${example}`)
    ].join('\n')
  }
  return formatCommandList()
}
