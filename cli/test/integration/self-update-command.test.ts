import { test, expect } from 'bun:test'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runCli } from '../helpers/run-cli'
import { CLI_PACKAGE_NAME } from '../../src/shared/constants'

test('self-update asks npm for its own package, forwarding only the npm registry', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'skill-center-npm-'))
  try {
    const capture = join(dir, 'calls.jsonl')
    await writeFile(join(dir, 'npm'), `#!/usr/bin/env node\nconst fs = require('fs');\nfs.appendFileSync(process.env.CLI_TEST_NPM_CAPTURE, JSON.stringify({args:process.argv.slice(2), registry:process.env.npm_config_registry})+'\\n');\nconsole.log(JSON.stringify('2.0.0'));\n`, { mode: 0o755 })
    const result = await runCli(['self-update', '--check', '--npm-registry', 'https://npm.example.test', '--registry', 'https://business.example.test', '--json'], {
      HOME: dir, USERPROFILE: dir, PATH: `${dir}:${process.env.PATH}`, CLI_TEST_NPM_CAPTURE: capture
    })
    expect(result.exitCode).toBe(0)
    expect(JSON.parse(result.stdout)).toMatchObject({ available: true, updated: false, latestVersion: '2.0.0' })
    const requests = (await readFile(capture, 'utf8')).trim().split('\n').map(line => JSON.parse(line))
    expect(requests).toEqual([{ args: ['view', `${CLI_PACKAGE_NAME}@latest`, 'version', '--json'], registry: 'https://npm.example.test' }])
    expect(result.stdout).not.toContain('@astron-team')
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})
