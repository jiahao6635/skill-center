import { describe, expect, test } from 'bun:test'
import { resolveRegistry, resolveToken } from '../../../src/services/registry-service'

describe('registry-service', () => {
  test('resolves registry by cli arg, env, config, default order', () => {
    expect(resolveRegistry({ registry: 'https://arg.example.com' }, {}, { registry: 'https://config.example.com' }))
      .toBe('https://arg.example.com')
    expect(resolveRegistry({}, { SKILLHUB_REGISTRY: 'https://env.example.com' }, { registry: 'https://config.example.com' }))
      .toBe('https://env.example.com')
    expect(resolveRegistry({}, {}, { registry: 'https://config.example.com' })).toBe('https://config.example.com')
    expect(resolveRegistry({}, {}, {})).toBe('https://skill-center.sigmob.com')
  })

  test('resolves token by cli arg, env, credentials order', () => {
    expect(resolveToken({ token: 'arg' }, {}, 'stored')).toBe('arg')
    expect(resolveToken({}, { SKILLHUB_TOKEN: 'env' }, 'stored')).toBe('env')
    expect(resolveToken({}, {}, 'stored')).toBe('stored')
  })
})

test('new environment names win and registry URLs cannot contain credentials', () => {
  expect(resolveRegistry({}, { SKILL_CENTER_REGISTRY: 'https://center.test', SKILLHUB_REGISTRY: 'https://old.test' }, {})).toBe('https://center.test')
  expect(resolveToken({}, { SKILL_CENTER_TOKEN: 'new', SKILLHUB_TOKEN: 'old' })).toBe('new')
  expect(() => resolveRegistry({ registry: 'https://user:secret@center.test' }, {}, {})).toThrow()
  expect(() => resolveRegistry({ registry: 'file:///tmp/server' }, {}, {})).toThrow()
})
