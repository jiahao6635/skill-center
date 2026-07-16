export interface ParsedSkillName {
  namespace: string
  slug: string
}

export function parseSkillName(skillName: string, defaultNamespace = 'global'): ParsedSkillName {
  const coordinate = skillName.startsWith('@') ? skillName.slice(1) : skillName
  const slashIndex = coordinate.indexOf('/')
  if (slashIndex > 0 && slashIndex < coordinate.length - 1) {
    return { namespace: coordinate.slice(0, slashIndex), slug: coordinate.slice(slashIndex + 1) }
  }
  const separatorIndex = coordinate.indexOf('--')

  if (separatorIndex <= 0) {
    return {
      namespace: defaultNamespace,
      slug: separatorIndex === 0 ? coordinate.slice(2) : coordinate
    }
  }

  if (separatorIndex === skillName.length - 2) {
    return {
      namespace: defaultNamespace,
      slug: coordinate.slice(0, -2)
    }
  }

  return {
    namespace: coordinate.slice(0, separatorIndex),
    slug: coordinate.slice(separatorIndex + 2)
  }
}
