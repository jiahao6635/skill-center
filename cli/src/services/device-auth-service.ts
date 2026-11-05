import { execFile } from 'node:child_process'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

export interface DeviceCodeResponse {
  deviceCode: string
  userCode: string
  verificationUri: string
  expiresIn: number
  interval: number
}

export interface DeviceTokenResponse {
  accessToken: string | null
  tokenType: string | null
  error: string | null
}

export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
}

export class DeviceAuthService {
  constructor(private readonly registry: string) {}

  async requestDeviceCode(): Promise<DeviceCodeResponse> {
    let response: Response
    try {
      response = await fetch(`${this.registry}/api/v1/auth/device/code`, {
        method: 'POST',
        signal: AbortSignal.timeout(30_000),
        redirect: 'error',
        headers: { 'Content-Type': 'application/json' }
      })
    } catch {
      throw new CliError('registry unreachable', EXIT.network, {
        registry: this.registry,
        next: 'check network or pass --registry'
      })
    }

    if (!response.ok) {
      throw new CliError(`failed to request device code (status ${response.status})`, EXIT.generic, {
        registry: this.registry
      })
    }

    const body = (await response.json()) as ApiEnvelope<DeviceCodeResponse>
    if (!body.data?.deviceCode || !body.data.userCode || typeof body.data.verificationUri !== 'string'
      || !Number.isFinite(body.data.expiresIn) || body.data.expiresIn <= 0 || body.data.expiresIn > 3600
      || !Number.isFinite(body.data.interval) || body.data.interval < 1) {
      throw new CliError('invalid device authorization response', EXIT.network)
    }
    return body.data
  }

  async pollForToken(
    deviceCode: string,
    interval: number,
    expiresIn: number
  ): Promise<string> {
    const deadline = Date.now() + expiresIn * 1000
    let pollInterval = interval * 1000

    while (Date.now() < deadline) {
      await this.sleep(pollInterval)

      let response: Response
      try {
        response = await fetch(`${this.registry}/api/v1/auth/device/token`, {
          method: 'POST',
          signal: AbortSignal.timeout(Math.max(1, Math.min(30_000, deadline - Date.now()))),
          redirect: 'error',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ deviceCode })
        })
      } catch {
        // Network hiccup, retry on next interval
        continue
      }

      if (!response.ok) {
        continue
      }

      const body = (await response.json()) as ApiEnvelope<DeviceTokenResponse>
      const data = body.data
      if (!data) throw new CliError('invalid device token response', EXIT.network)

      if (data.accessToken) {
        return data.accessToken
      }

      if (data.error === 'authorization_pending') {
        continue
      }

      if (data.error === 'slow_down') {
        pollInterval += 5000
        continue
      }

      if (data.error === 'expired_token') {
        throw new CliError('device code expired', EXIT.auth, {
          next: 'run login --browser again'
        })
      }

      if (data.error === 'access_denied') {
        throw new CliError('authorization denied', EXIT.auth, {
          next: 'the user denied the authorization request'
        })
      }

      throw new CliError(`device auth failed: ${data.error || 'unknown error'}`, EXIT.auth)
    }

    throw new CliError('device code expired (timeout)', EXIT.auth, {
      next: 'run login --browser again'
    })
  }

  buildVerificationUrl(verificationUri: string, userCode: string): string {
    const url = new URL(verificationUri, `${this.registry}/`)
    if (url.origin !== new URL(this.registry).origin || url.username || url.password) {
      throw new CliError('authorization URL must belong to the configured server', EXIT.auth)
    }
    url.searchParams.set('user_code', userCode)
    return url.toString()
  }

  openBrowser(url: string): void {
    const platform = process.platform
    const command = platform === 'darwin' ? 'open' : platform === 'win32' ? 'rundll32.exe' : 'xdg-open'
    execFile(command, platform === 'win32' ? ['url.dll,FileProtocolHandler', url] : [url], () => {
      // The URL is also printed for headless environments.
    })
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }
}
