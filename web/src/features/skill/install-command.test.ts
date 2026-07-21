import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  InstallCommand,
  buildInstallCommand,
  buildInstallTarget,
  buildSkillhubInstallCommand,
  getBaseUrl,
} from './install-command.tsx'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

describe('install-command', () => {
  const originalWindow = globalThis.window

  function setMockWindow(appBaseUrl?: string) {
    const location = {
      protocol: 'https:',
      host: 'fallback.example.com',
    } satisfies Pick<Location, 'protocol' | 'host'>

    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      writable: true,
      value: {
        __SKILLHUB_RUNTIME_CONFIG__: {
          appBaseUrl,
        },
        location,
      } satisfies {
        location: Pick<Location, 'protocol' | 'host'>
      } & {
        __SKILLHUB_RUNTIME_CONFIG__: {
          appBaseUrl?: string
        }
      },
    })
  }

  afterEach(() => {
    if (originalWindow) {
      Object.defineProperty(globalThis, 'window', {
        configurable: true,
        writable: true,
        value: originalWindow,
      })
      return
    }
    Reflect.deleteProperty(globalThis, 'window')
  })

  it('uses the plain slug for the global namespace', () => {
    expect(buildInstallTarget('global', 'my-skill')).toBe('my-skill')
    expect(buildInstallCommand('global', 'my-skill', 'https://skill.xfyun.cn')).toBe(
      'npx clawhub install my-skill --registry https://skill.xfyun.cn',
    )
  })

  it('prefixes non-global namespaces in the install target', () => {
    expect(buildInstallTarget('team-alpha', 'my-skill')).toBe('team-alpha--my-skill')
    expect(buildInstallCommand('team-alpha', 'my-skill', 'https://skill.xfyun.cn')).toBe(
      'npx clawhub install team-alpha--my-skill --registry https://skill.xfyun.cn',
    )
  })

  it('builds a one-line Skill Center npx command for the global namespace', () => {
    expect(buildSkillhubInstallCommand('global', 'my-skill', 'https://skill.xfyun.cn')).toBe(
      'npx @astron-team/skillhub@latest install my-skill --registry https://skill.xfyun.cn',
    )
  })

  it('builds a one-line Skill Center npx command with namespace for team skills', () => {
    expect(buildSkillhubInstallCommand('team-alpha', 'my-skill', 'https://skill.xfyun.cn')).toBe(
      'npx @astron-team/skillhub@latest install my-skill --namespace team-alpha --registry https://skill.xfyun.cn',
    )
  })

  it('uses the runtime app base url when available', () => {
    setMockWindow('https://app.example.com')

    expect(getBaseUrl()).toBe('https://app.example.com')
  })

  it('falls back to the browser origin when the app base url is missing', () => {
    setMockWindow()
    expect(getBaseUrl()).toBe('https://fallback.example.com')
  })

  it('falls back to browser origin when app base url is localhost', () => {
    setMockWindow('http://localhost')
    expect(getBaseUrl()).toBe('https://fallback.example.com')
  })

  it('falls back to browser origin when app base url contains localhost', () => {
    setMockWindow('http://localhost:8080')
    expect(getBaseUrl()).toBe('https://fallback.example.com')
  })

  it('renders the install command in a more compact code block', () => {
    setMockWindow('http://localhost:3000')

    const html = renderToStaticMarkup(createElement(InstallCommand, { namespace: 'global', slug: 'meeting-minutes-generator' }))

    expect(html).toContain('px-4 py-3')
    expect(html).toContain('leading-relaxed')
    expect(html).toContain('break-all')
  })

  it('renders the agent prompt with skill-center instruction and installer fallback', () => {
    setMockWindow('https://app.example.com')

    const html = renderToStaticMarkup(createElement(InstallCommand, {
      namespace: 'global',
      slug: 'meeting-minutes-generator',
    }))

    expect(html).toContain('使用 skill-center 技能帮我安装 meeting-minutes-generator')
    expect(html).toContain('如果还没有安装 skill-center 技能，请先安装：https://app.example.com/registry/skill-center-installer.md')
  })

  it('renders the agent prompt with namespace-prefixed target for team skills', () => {
    setMockWindow('https://app.example.com')

    const html = renderToStaticMarkup(createElement(InstallCommand, {
      namespace: 'team-alpha',
      slug: 'meeting-minutes-generator',
    }))

    expect(html).toContain('使用 skill-center 技能帮我安装 team-alpha--meeting-minutes-generator')
    expect(html).toContain('如果还没有安装 skill-center 技能，请先安装：https://app.example.com/registry/skill-center-installer.md')
  })
})
