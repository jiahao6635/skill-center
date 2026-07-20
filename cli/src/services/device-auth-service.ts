import { exec } from 'child_process'
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
    // verificationUri might be a path like "/cli/auth" or a full URL
    const base = verificationUri.startsWith('http')
      ? verificationUri
      : `${this.registry}${verificationUri}`
    const separator = base.includes('?') ? '&' : '?'
    return `${base}${separator}user_code=${encodeURIComponent(userCode)}`
  }

  openBrowser(url: string): void {
    const platform = process.platform
    let command: string

    if (platform === 'darwin') {
      command = `open "${url}"`
    } else if (platform === 'win32') {
      command = `start "${url}"`
    } else {
      command = `xdg-open "${url}"`
    }

    exec(command, (err) => {
      if (err) {
        // Silently fail - user can open manually
      }
    })
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }
}
