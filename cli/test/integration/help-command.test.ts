import { describe, expect, test } from 'bun:test'
import { runCli } from '../helpers/run-cli'

describe('help command', () => {
  test('describes installation options', async () => {
    const result = await runCli(['help', 'install'])
    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Usage: skill-center install <slug>')
    expect(result.stdout).toContain('--agent <profile>')
  })
  test('lists management commands as machine-readable JSON', async () => {
    const result = await runCli(['help', '--json'])
    expect(result.exitCode).toBe(0)
    expect(JSON.parse(result.stdout).commands.map((item: { name: string }) => item.name))
      .toEqual(expect.arrayContaining(['inspect', 'update', 'move', 'move-status', 'withdraw', 'self-update']))
  })
  test('returns topic help as JSON', async () => {
    const result = await runCli(['help', 'update', '--json'])
    expect(result.exitCode).toBe(0)
    expect(JSON.parse(result.stdout).usage).toContain('<skill-id> <path>')
  })
  test('rejects an unknown topic without crashing', async () => {
    const result = await runCli(['help', 'unknown', '--json'])
    expect(result.exitCode).toBe(5)
    expect(JSON.parse(result.stderr).message).toBe('unknown command: unknown')
  })
})
