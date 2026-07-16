import { spawn } from 'node:child_process'

const SERVICE = 'skillhub-cli'

interface ProcessResult {
  code: number
  stdout: string
}

async function run(command: string, args: string[], stdin?: string): Promise<ProcessResult> {
  return new Promise((resolve) => {
    const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'ignore'] })
    const chunks: Buffer[] = []
    child.stdout.on('data', chunk => chunks.push(Buffer.from(chunk)))
    child.on('error', () => resolve({ code: -1, stdout: '' }))
    child.on('close', code => resolve({ code: code ?? -1, stdout: Buffer.concat(chunks).toString('utf8').trim() }))
    if (stdin !== undefined) child.stdin.end(stdin)
    else child.stdin.end()
  })
}

export class SystemCredentialStore {
  isEnabled(): boolean {
    return process.env.SKILLHUB_CREDENTIAL_STORE !== 'file' && process.env.NODE_ENV !== 'test'
  }

  async get(registry: string): Promise<string | undefined> {
    if (!this.isEnabled()) return undefined
    if (process.platform === 'darwin') {
      const result = await run('security', ['find-generic-password', '-a', registry, '-s', SERVICE, '-w'])
      return result.code === 0 && result.stdout ? result.stdout : undefined
    }
    if (process.platform === 'linux') {
      const result = await run('secret-tool', ['lookup', 'service', SERVICE, 'registry', registry])
      return result.code === 0 && result.stdout ? result.stdout : undefined
    }
    return undefined
  }

  async set(registry: string, token: string): Promise<boolean> {
    if (!this.isEnabled()) return false
    if (process.platform === 'darwin') {
      const result = await run(
        'security',
        ['add-generic-password', '-U', '-a', registry, '-s', SERVICE, '-w'],
        token,
      )
      return result.code === 0
    }
    if (process.platform === 'linux') {
      const result = await run(
        'secret-tool',
        ['store', '--label', 'SkillHub CLI token', 'service', SERVICE, 'registry', registry],
        token,
      )
      return result.code === 0
    }
    return false
  }

  async delete(registry: string): Promise<void> {
    if (!this.isEnabled()) return
    if (process.platform === 'darwin') {
      await run('security', ['delete-generic-password', '-a', registry, '-s', SERVICE])
    } else if (process.platform === 'linux') {
      await run('secret-tool', ['clear', 'service', SERVICE, 'registry', registry])
    }
  }
}
