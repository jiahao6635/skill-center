import { CliError } from './errors'
import { randomUUID } from 'node:crypto'

export type JsonObject = Record<string, unknown>

export function printResult(result: string | JsonObject, json: boolean): string {
  if (json) {
    return JSON.stringify(typeof result === 'string' ? { ok: true, message: result } : result)
  }
  return typeof result === 'string' ? result : humanize(result)
}

export function renderError(error: unknown, json: boolean): string {
  const cliError = error instanceof CliError
    ? error
    : new CliError('unexpected failure', 1)

  if (json) {
    const detailsCode = typeof cliError.details.code === 'string' ? cliError.details.code : undefined
    const requestId = typeof cliError.details.requestId === 'string' ? cliError.details.requestId : randomUUID()
    return JSON.stringify({
      ok: false,
      schemaVersion: 1,
      command: currentCommand(),
      code: detailsCode ?? errorCode(cliError.exitCode),
      message: cliError.message,
      exitCode: cliError.exitCode,
      requestId,
      ...(Object.keys(cliError.details).length > 0 ? { details: cliError.details } : {})
    })
  }

  const lines = [`Error: ${cliError.message}`]
  if (typeof cliError.details.registry === 'string') {
    lines.push(`Context: registry ${cliError.details.registry}`)
  }
  if (typeof cliError.details.path === 'string') {
    lines.push(`Context: path ${cliError.details.path}`)
  }
  if (typeof cliError.details.next === 'string') {
    lines.push(`Next: ${cliError.details.next}`)
  }
  return lines.join('\n')
}

function currentCommand(): string {
  const args = process.argv.slice(2).filter(arg => !arg.startsWith('-'))
  const root = args[0] ?? 'unknown'
  if (['skill', 'review', 'external'].includes(root) && args[1]) return `${root}.${args[1]}`
  if (root === 'publish' && process.argv.includes('--dry-run')) return 'publish.validate'
  return root
}

function errorCode(exitCode: number): string {
  return ({
    1: 'COMMAND_FAILED',
    2: 'AUTHENTICATION_REQUIRED',
    3: 'NETWORK_ERROR',
    4: 'FILESYSTEM_ERROR',
    5: 'USAGE_ERROR',
    6: 'PACKAGE_VALIDATION_FAILED',
    7: 'CONFIRMATION_REQUIRED',
    8: 'STATE_CONFLICT',
    9: 'NOT_FOUND',
  } as Record<number, string>)[exitCode] ?? 'COMMAND_FAILED'
}

function humanize(value: JsonObject): string {
  return Object.entries(value)
    .filter(([key]) => key !== 'ok')
    .map(([key, item]) => `${key}: ${String(item)}`)
    .join('\n')
}
