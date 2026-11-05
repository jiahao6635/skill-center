import { expect, test } from 'bun:test'
import { DeviceAuthService } from '../../../src/services/device-auth-service'

test('device login keeps authorization on the configured server and encodes the code', () => {
  const service = new DeviceAuthService('https://center.example.test')
  expect(service.buildVerificationUrl('/cli/auth', 'A B&')).toBe('https://center.example.test/cli/auth?user_code=A+B%26')
  expect(() => service.buildVerificationUrl('https://other.example.test/auth', 'code')).toThrow()
  expect(() => service.buildVerificationUrl('javascript:alert(1)', 'code')).toThrow()
})

test('device login rejects malformed code responses and surfaces denied authorization', async () => {
  const server = Bun.serve({ port: 0, fetch(request) {
    return Response.json({ code: 0, data: new URL(request.url).pathname.endsWith('/code')
      ? { deviceCode: 'code', userCode: 'ABCD', verificationUri: '/cli/auth', expiresIn: -1, interval: 1 }
      : { error: 'access_denied' } })
  } })
  try {
    const service = new DeviceAuthService(`http://localhost:${server.port}`)
    await expect(service.requestDeviceCode()).rejects.toThrow('invalid device authorization response')
    await expect(service.pollForToken('code', 0.001, 1)).rejects.toThrow('authorization denied')
  } finally {
    server.stop(true)
  }
})
