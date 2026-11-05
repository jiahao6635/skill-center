import { CliError } from './errors'

export interface ParsedSkillName {
  namespace: string
  slug: string
}

export function parseSkillName(skillName: string, defaultNamespace = 'global'): ParsedSkillName {
  if (skillName.includes('/')) {
    const parts = skillName.replace(/^@/, '').split('/')
    if (parts.length !== 2 || !parts[0] || !parts[1] || parts.some(part => !/^[a-z0-9][a-z0-9-]*$/.test(part))) {
      throw new CliError('invalid skill coordinate; use @namespace/slug', 5)
    }
    return { namespace: parts[0], slug: parts[1] }
  }
  const separatorIndex = skillName.indexOf('--')

  if (separatorIndex <= 0) {
    return {
      namespace: defaultNamespace,
      slug: separatorIndex === 0 ? skillName.slice(2) : skillName
    }
  }

  if (separatorIndex === skillName.length - 2) {
    return {
      namespace: defaultNamespace,
      slug: skillName.slice(0, -2)
    }
  }

  return {
    namespace: skillName.slice(0, separatorIndex),
    slug: skillName.slice(separatorIndex + 2)
  }
}
