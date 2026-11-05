import { describe, expect, test } from 'bun:test'
import { runCli } from '../helpers/run-cli'

describe('CLI usage errors', () => {
  for (const args of [['foo'], ['foo', '--bad'], ['version', '--badflag'], ['install']]) {
    test(`rejects ${args.join(' ')}`, async () => {
      const result = await runCli([...args, '--json'])
      expect(result.exitCode).toBe(5)
      expect(result.stdout).toBe('')
      expect(JSON.parse(result.stderr)).toMatchObject({ ok: false, exitCode: 5 })
      expect(result.stderr).not.toContain('at <anonymous>')
    })
  }
  test('unknown command gives an actionable message', async () => {
    const result = await runCli(['serch'])
    expect(result.stderr).toContain('unknown command "serch"')
    expect(result.stderr).toContain('skill-center help')
  })
})
