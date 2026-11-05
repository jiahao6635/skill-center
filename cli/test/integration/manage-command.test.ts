import { afterEach, describe, expect, test } from 'bun:test'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runCli } from '../helpers/run-cli'

const temporary: string[] = []
const servers: Array<ReturnType<typeof Bun.serve>> = []
afterEach(async () => {
  servers.splice(0).forEach(server => server.stop(true))
  await Promise.all(temporary.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

async function setup(options: { warnings?: string[]; valid?: boolean; failSubmit?: boolean } = {}) {
  const home = await mkdtemp(join(tmpdir(), 'skill-center-management-'))
  temporary.push(home)
  await writeFile(join(home, 'SKILL.md'), '---\nname: demo\ndescription: test\n---\n# Demo')
  const calls: Array<{ path: string; method: string; fields: Record<string, unknown> }> = []
  const server = Bun.serve({
    port: 0,
    async fetch(request) {
      const path = new URL(request.url).pathname
      if (request.headers.get('Authorization') !== 'Bearer test-token') return Response.json({ msg: 'unauthorized' }, { status: 401 })
      const fields: Record<string, unknown> = {}
      if (request.headers.get('content-type')?.includes('multipart/form-data')) {
        const form = await request.formData()
        for (const [key, value] of form) fields[key] = value instanceof File ? value.name : value
      } else if (request.headers.get('content-type')?.includes('application/json')) {
        Object.assign(fields, await request.json())
      }
      calls.push({ path, method: request.method, fields })
      if (path.endsWith('/versions') && request.method === 'POST') {
        return Response.json({ code: 0, data: { skillId: 42, namespace: 'team', slug: 'demo', version: '2.0.0', status: 'SCANNING' } })
      }
      if (path.endsWith('/sharing/precheck')) return Response.json({ code: 0, data: { valid: options.valid ?? true, errors: [], warnings: options.warnings ?? [] } })
      if (path.endsWith('/sharing') && request.method === 'GET') return Response.json({ code: 0, data: {
        skillId: 42, slug: 'demo', namespace: 'private', versions: [{ version: '2.0.0' }], targets: [{ id: 8, slug: 'team', displayName: 'Team' }], latestRequest: { id: 123, status: 'SCANNING' }
      } })
      if (path.endsWith('/sharing') && request.method === 'POST') {
        return options.failSubmit ? Response.json({ code: 503, msg: 'unavailable' }, { status: 503 })
          : Response.json({ code: 0, data: { id: 123, status: 'SCANNING', targetNamespace: 'team' } })
      }
      if (path.endsWith('/withdraw')) return Response.json({ code: 0, data: { id: 123, status: 'WITHDRAWN' } })
      if (path.endsWith('/location')) return Response.json({ code: 0, data: { namespace: 'team', slug: 'demo' } })
      if (path === '/api/v1/skills/team/demo') return Response.json({ code: 0, data: { id: 42, namespace: 'team', slug: 'demo', headlineVersion: { version: '2.0.0', status: 'SCANNING' } } })
      if (path === '/api/v1/namespaces') return Response.json({ code: 0, data: { items: [{ id: 8, slug: 'team' }], total: 1 } })
      return Response.json({ code: 404 }, { status: 404 })
    }
  })
  servers.push(server)
  const run = (args: string[]) => runCli([...args, '--registry', `http://localhost:${server.port}`, '--json'], {
    HOME: home, USERPROFILE: home, SKILL_CENTER_TOKEN: 'test-token'
  }, { cwd: home })
  return { home, calls, run }
}

describe('native Skill Center management', () => {
  test('updates by stable ID without changing visibility or namespace, and reports scanning', async () => {
    const { home, calls, run } = await setup()
    const result = await run(['update', '42', home, '--confirm-warnings'])
    expect(result.exitCode).toBe(0)
    expect(JSON.parse(result.stdout)).toMatchObject({ skillId: 42, status: 'SCANNING' })
    expect(calls).toHaveLength(1)
    expect(calls[0]).toMatchObject({ path: '/api/v1/skills/by-id/42/versions', method: 'POST' })
    expect(calls[0]!.fields).toEqual({ file: `${home.split('/').pop()}.zip`, confirmWarnings: 'true' })
    const invalid = await run(['update', '42', home, '--namespace', 'other'])
    expect(invalid.exitCode).toBe(5)
    expect(calls).toHaveLength(1)
  })

  test('moves only after precheck; warning acknowledgement and idempotency are explicit', async () => {
    const { run, calls } = await setup({ warnings: ['review contents'] })
    const preview = await run(['move', '42', 'team', '--dry-run'])
    expect(preview.exitCode).toBe(0)
    expect(JSON.parse(preview.stdout).warnings).toEqual(['review contents'])
    expect(calls.filter(call => call.method === 'POST').map(call => call.path)).toEqual(['/api/v1/skills/by-id/42/sharing/precheck'])
    const blocked = await run(['move', '42', 'team'])
    expect(blocked.exitCode).toBe(6)
    expect(JSON.parse(blocked.stderr).details.warnings).toEqual(['review contents'])
    const submitted = await run(['move', '42', 'team', '--confirm-warnings', '--idempotency-key', 'retry-42'])
    expect(submitted.exitCode).toBe(0)
    expect(JSON.parse(submitted.stdout)).toMatchObject({ id: 123, status: 'SCANNING', idempotencyKey: 'retry-42' })
    expect(calls.at(-1)).toEqual({ path: '/api/v1/skills/by-id/42/sharing', method: 'POST', fields: { targetNamespaceId: 8, idempotencyKey: 'retry-42', confirmWarnings: true } })
    expect(calls.at(-2)!.fields).toEqual(calls.at(-1)!.fields)
    const status = await run(['move-status', '42'])
    expect(JSON.parse(status.stdout).latestRequest.status).toBe('SCANNING')
    const withdrawn = await run(['withdraw', '42', '123'])
    expect(JSON.parse(withdrawn.stdout).status).toBe('WITHDRAWN')
  })

  test('does not submit invalid moves, and preserves retry key after an uncertain response', async () => {
    const invalid = await setup({ valid: false })
    expect((await invalid.run(['move', '42', 'team'])).exitCode).toBe(6)
    expect(invalid.calls.some(call => call.path.endsWith('/sharing') && call.method === 'POST')).toBe(false)
    const failed = await setup({ failSubmit: true })
    const result = await failed.run(['move', '42', 'team', '--idempotency-key', 'same-request'])
    expect(result.exitCode).toBe(3)
    expect(JSON.parse(result.stderr).details.idempotencyKey).toBe('same-request')
  })

  test('resolves moved skills by ID and accepts native coordinates; namespaces remain paginated', async () => {
    const { run, calls } = await setup()
    expect(JSON.parse((await run(['inspect', '42'])).stdout).namespace).toBe('team')
    expect(calls.slice(0, 2).map(call => call.path)).toEqual(['/api/v1/skills/by-id/42/location', '/api/v1/skills/team/demo'])
    expect(JSON.parse((await run(['inspect', '@team/demo'])).stdout).id).toBe(42)
    expect(JSON.parse((await run(['namespaces'])).stdout).total).toBe(1)
    const before = calls.length
    expect((await run(['move', '../42', 'team'])).exitCode).toBe(5)
    expect((await run(['namespaces', '--size', '0'])).exitCode).toBe(5)
    expect(calls.length).toBe(before)
  })
})

test('removing a native coordinate preserves other namespaces with the same slug', async () => {
  const { home, run } = await setup()
  const { mkdir, access } = await import('node:fs/promises')
  const registry = `http://localhost:${servers.at(-1)!.port}`
  const items = []
  for (const namespace of ['team', 'other']) {
    const rootDir = join(home, namespace)
    const installDir = join(rootDir, 'demo')
    await mkdir(installDir, { recursive: true })
    items.push({ registry, namespace, slug: 'demo', version: '1.0.0', targets: [{ agent: 'custom', rootDir, installDir, installedAt: new Date().toISOString() }] })
  }
  await mkdir(join(home, '.skillhub'))
  await writeFile(join(home, '.skillhub', 'inventory.json'), JSON.stringify({ items }))
  const result = await run(['remove', '@team/demo'])
  expect(result.exitCode).toBe(0)
  expect(JSON.parse(result.stdout).removed.map((item: { namespace: string }) => item.namespace)).toEqual(['team'])
  await access(join(home, 'other', 'demo'))
  await expect(access(join(home, 'team', 'demo'))).rejects.toThrow()
})
