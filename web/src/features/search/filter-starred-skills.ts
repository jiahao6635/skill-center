import type { SkillSummary } from '@/api/types.ts'

export function authorNameEquals(left: string | undefined, right: string): boolean {
  return (left ?? '').trim().toLocaleLowerCase() === right.trim().toLocaleLowerCase()
}

export function filterStarredSkills(
  skills: SkillSummary[],
  query: string,
  namespace: string,
  author = '',
): SkillSummary[] {
  const normalizedQuery = query.trim().toLowerCase()
  const normalizedNamespace = namespace.trim().toLowerCase()
  const normalizedAuthor = author.trim()

  return skills.filter((skill) => {
    if (normalizedAuthor && !authorNameEquals(skill.ownerDisplayName, normalizedAuthor)) {
      return false
    }
    const matchesNamespace = !normalizedNamespace || skill.namespace.toLowerCase() === normalizedNamespace
    if (!matchesNamespace) {
      return false
    }
    if (!normalizedQuery) {
      return true
    }
    return [skill.displayName, skill.summary, skill.namespace, skill.slug]
      .filter(Boolean)
      .some((value) => value!.toLowerCase().includes(normalizedQuery))
  })
}
