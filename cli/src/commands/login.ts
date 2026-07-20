import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { AuthService } from '../services/auth-service'
import { DeviceAuthService } from '../services/device-auth-service'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { SkillHubClient } from '../clients/skillhub-client'

export interface LoginCommandOptions {
  registry?: string
  token?: string
  browser?: boolean
  json?: boolean
}

export async function loginCommand(options: LoginCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const credentialsStore = new CredentialsStore()
  const registry = resolveRegistry(options, process.env, await configStore.read())

  // Browser-based device auth flow
  if (options.browser && !options.token) {
    return loginWithBrowser(registry, configStore, credentialsStore, options.json)
  }

  const token = resolveToken(options, process.env, await credentialsStore.getToken(registry))
  const result = await new AuthService(configStore, credentialsStore).login(registry, token)
  return options.json
    ? JSON.stringify({ ok: true, registry, handle: result.handle })
    : `Logged in to ${registry} as ${result.handle}`
}

async function loginWithBrowser(
  registry: string,
  configStore: ConfigStore,
  credentialsStore: CredentialsStore,
  json?: boolean
): Promise<string> {
  const deviceAuth = new DeviceAuthService(registry)

  // Step 1: Request device code
  const codeResponse = await deviceAuth.requestDeviceCode()
  const verificationUrl = deviceAuth.buildVerificationUrl(
    codeResponse.verificationUri,
    codeResponse.userCode
  )

  if (!json) {
    console.log(`\nPlease open the following URL in your browser to authorize:`)
    console.log(`\n  ${verificationUrl}\n`)
    console.log(`Your verification code: ${codeResponse.userCode}`)
    console.log(`\nWaiting for authorization...`)
  }

  // Step 2: Try to open browser automatically
  deviceAuth.openBrowser(verificationUrl)

  // Step 3: Poll for token
  const token = await deviceAuth.pollForToken(
    codeResponse.deviceCode,
    codeResponse.interval,
    codeResponse.expiresIn
  )

  // Step 4: Verify token and store
  const user = await new SkillHubClient(registry, token).whoami()
  await configStore.setRegistry(registry)
  await credentialsStore.setToken(registry, token)

  return json
    ? JSON.stringify({ ok: true, registry, handle: user.handle })
    : `\u2713 Logged in to ${registry} as ${user.handle}`
}
