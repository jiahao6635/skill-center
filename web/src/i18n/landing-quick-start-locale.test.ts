import { describe, expect, it } from 'vitest'
import en from './locales/en.json'
import zh from './locales/zh.json'

describe('landing quick start locales', () => {
  it('uses localized agent setup prompts for chinese and english', () => {
    expect(zh.landing.quickStart.agent.command).toBe('帮我安装 Skill Center：https://skill-center.sigmob.com/registry/skill-center-installer.md')
    expect(en.landing.quickStart.agent.command).toBe('Install Skill Center for me: https://skill-center.sigmob.com/registry/skill-center-installer.md')
  })

  it('provides command templates with url placeholder for dynamic rendering', () => {
    expect(zh.landing.quickStart.agent.commandTemplate).toBe('帮我安装 Skill Center：{{url}}')
    expect(en.landing.quickStart.agent.commandTemplate).toBe('Install Skill Center for me: {{url}}')
  })

  it('exposes CLI install command in both locales', () => {
    expect(zh.landing.quickStart.tabs.cli).toBe('CLI')
    expect(zh.landing.quickStart.cli.command).toBe('npm i -g @astron-team/skillhub')
    expect(zh.landing.quickStart.cli.description).toBe('安装 Skill Center CLI 到本地，后续可运行 skillhub install 安装技能')
    expect(en.landing.quickStart.tabs.cli).toBe('CLI')
    expect(en.landing.quickStart.cli.command).toBe('npm i -g @astron-team/skillhub')
    expect(en.landing.quickStart.cli.description).toBe('Install the Skill Center CLI locally to run skillhub install for skills.')
  })
})
