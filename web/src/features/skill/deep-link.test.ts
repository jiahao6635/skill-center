import { describe, it, expect } from 'vitest'
import {
  decodeConfigParam,
  encodeConfigParam,
  buildProtocolUrl,
  buildDeepLinkUrl,
  validateSkillName,
  validateConfig,
  type SkillInstallConfig,
} from './deep-link.ts'

describe('decodeConfigParam', () => {
  const validConfig: SkillInstallConfig = {
    scope: 'team',
    version: '1.0.0',
    skill_name: 'my-skill',
    description: 'A test skill',
    download_url: 'https://example.com/download',
    source: 'official',
  }

  it('decodes standard base64', () => {
    const encoded = btoa(JSON.stringify(validConfig))
    const result = decodeConfigParam(encoded)
    expect(result).toEqual(validConfig)
  })

  it('handles URL-safe variant with - and _', () => {
    // Create a config that produces + and / in base64
    const config: SkillInstallConfig = {
      scope: 'global',
      download_url: 'https://example.com/path?a=1&b=2',
      description: 'Contains special chars: >>> ???',
    }
    const json = JSON.stringify(config)
    // Standard base64
    const stdBase64 = btoa(json)
    // URL-safe base64: replace + → -, / → _, strip =
    const urlSafe = stdBase64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const result = decodeConfigParam(urlSafe)
    expect(result).toEqual(config)
  })

  it('restores spaces that replaced + during URL transport', () => {
    const encoded = btoa(JSON.stringify(validConfig))
    // Simulate URL transport replacing + with space
    const withSpaces = encoded.replace(/\+/g, ' ')
    // Only test if the original actually has + signs
    if (encoded.includes('+')) {
      const result = decodeConfigParam(withSpaces)
      expect(result).toEqual(validConfig)
    } else {
      // If no + in original, spaces should still decode fine
      const result = decodeConfigParam(withSpaces)
      expect(result).toEqual(validConfig)
    }
  })

  it('handles missing padding', () => {
    const encoded = btoa(JSON.stringify(validConfig))
    // Strip trailing =
    const stripped = encoded.replace(/=+$/, '')
    const result = decodeConfigParam(stripped)
    expect(result).toEqual(validConfig)
  })

  it('returns null for invalid base64', () => {
    expect(decodeConfigParam('!!!not-valid-base64!!!')).toBeNull()
  })

  it('returns null for valid base64 but invalid JSON', () => {
    const encoded = btoa('not json at all')
    expect(decodeConfigParam(encoded)).toBeNull()
  })

  it('returns null for JSON arrays', () => {
    const encoded = btoa(JSON.stringify([1, 2, 3]))
    expect(decodeConfigParam(encoded)).toBeNull()
  })

  it('returns null for JSON primitives', () => {
    expect(decodeConfigParam(btoa('"string"'))).toBeNull()
    expect(decodeConfigParam(btoa('42'))).toBeNull()
    expect(decodeConfigParam(btoa('null'))).toBeNull()
  })

  it('returns null for empty string', () => {
    expect(decodeConfigParam('')).toBeNull()
  })
})

describe('encodeConfigParam', () => {
  it('encodes config to base64 that round-trips through decode', () => {
    const config: SkillInstallConfig = {
      scope: 'team',
      version: '2.0.0',
      skill_name: 'test-skill',
      download_url: 'https://example.com/dl',
    }
    const encoded = encodeConfigParam(config)
    const decoded = decodeConfigParam(encoded)
    expect(decoded).toEqual(config)
  })
})

describe('buildProtocolUrl', () => {
  it('builds correct qoder-work protocol URL', () => {
    const url = buildProtocolUrl('my-skill', 'eyJzY29wZSI6InRlYW0ifQ==')
    expect(url).toBe('qoder-work://skill/install?name=my-skill&config=eyJzY29wZSI6InRlYW0ifQ%3D%3D')
  })

  it('encodes special characters in name', () => {
    const url = buildProtocolUrl('my-skill_v2', 'abc')
    expect(url).toContain('name=my-skill_v2')
    expect(url.startsWith('qoder-work://skill/install?')).toBe(true)
  })
})

describe('buildDeepLinkUrl', () => {
  it('builds correct web intermediate URL', () => {
    const url = buildDeepLinkUrl('https://skill-center.example.com', 'my-skill', 'base64data')
    expect(url).toBe('https://skill-center.example.com/link/skill/install?name=my-skill&config=base64data')
  })

  it('trims trailing slashes from baseUrl', () => {
    const url = buildDeepLinkUrl('https://example.com/', 'skill', 'cfg')
    expect(url).toBe('https://example.com/link/skill/install?name=skill&config=cfg')
  })

  it('trims multiple trailing slashes', () => {
    const url = buildDeepLinkUrl('https://example.com///', 'skill', 'cfg')
    expect(url).toBe('https://example.com/link/skill/install?name=skill&config=cfg')
  })
})

describe('validateSkillName', () => {
  it('accepts valid names', () => {
    expect(validateSkillName('my-skill')).toBe(true)
    expect(validateSkillName('skill_v2')).toBe(true)
    expect(validateSkillName('abc123')).toBe(true)
    expect(validateSkillName('a')).toBe(true)
  })

  it('rejects invalid names', () => {
    expect(validateSkillName('My-Skill')).toBe(false) // uppercase
    expect(validateSkillName('my skill')).toBe(false) // space
    expect(validateSkillName('my.skill')).toBe(false) // dot
    expect(validateSkillName('')).toBe(false) // empty
    expect(validateSkillName('skill@v2')).toBe(false) // special char
    expect(validateSkillName('skill/name')).toBe(false) // slash
  })
})

describe('validateConfig', () => {
  it('returns empty array for valid config', () => {
    const config: SkillInstallConfig = {
      scope: 'team',
      download_url: 'https://example.com/dl',
    }
    expect(validateConfig(config)).toEqual([])
  })

  it('reports missing scope', () => {
    const config = { download_url: 'https://example.com/dl' } as SkillInstallConfig
    expect(validateConfig(config)).toEqual(['scope'])
  })

  it('reports missing download_url', () => {
    const config = { scope: 'team' } as SkillInstallConfig
    expect(validateConfig(config)).toEqual(['download_url'])
  })

  it('reports both missing fields', () => {
    const config = {} as SkillInstallConfig
    expect(validateConfig(config)).toEqual(['scope', 'download_url'])
  })
})
