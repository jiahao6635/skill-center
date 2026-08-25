export function searchEmptyCopy(input: {
  namespaceUnavailable: boolean
  starredOnly: boolean
  author: string
  namespace: string
  q: string
  t: (key: string, options?: Record<string, unknown>) => string
}): { title: string; description?: string } {
  const author = input.author.trim()
  const hasAuthor = Boolean(author)
  const hasQ = Boolean(input.q)
  const hasNamespace = Boolean(input.namespace)
  const authorHint = input.t('search.noResultsForAuthorHint')

  if (input.namespaceUnavailable) {
    return {
      title: input.t('search.namespaceUnavailable'),
      description: input.t('search.namespaceUnavailableHint'),
    }
  }

  if (input.starredOnly) {
    if (hasAuthor && hasQ) {
      return {
        title: input.t('search.noStarredResultsForQueryAndAuthor', { author, q: input.q }),
        description: authorHint,
      }
    }
    if (hasAuthor) {
      return {
        title: input.t('search.noStarredResultsForAuthor', { author }),
        description: authorHint,
      }
    }
    return {
      title: input.t('search.noStarredResults'),
      description: hasQ
        ? input.t('search.noStarredResultsFor', { q: input.q })
        : input.t('search.noStarredSkills'),
    }
  }

  if (hasAuthor && hasNamespace && hasQ) {
    return {
      title: input.t('search.noResultsForQueryAuthorInNamespace', {
        author,
        namespace: input.namespace,
        q: input.q,
      }),
      description: authorHint,
    }
  }
  if (hasAuthor && hasNamespace) {
    return {
      title: input.t('search.noResultsForAuthorInNamespace', { author, namespace: input.namespace }),
      description: authorHint,
    }
  }
  if (hasAuthor && hasQ) {
    return {
      title: input.t('search.noResultsForQueryAndAuthor', { author, q: input.q }),
      description: authorHint,
    }
  }
  if (hasAuthor) {
    return {
      title: input.t('search.noResultsForAuthor', { author }),
      description: authorHint,
    }
  }

  if (hasNamespace && !hasQ) {
    return {
      title: input.t('search.noResultsInNamespace', { namespace: input.namespace }),
      description: input.t('search.noResultsInNamespaceHint'),
    }
  }
  if (hasNamespace && hasQ) {
    return {
      title: input.t('search.noResults', { namespace: input.namespace }),
      description: input.t('search.noResultsForInNamespace', { q: input.q, namespace: input.namespace }),
    }
  }
  if (hasQ) {
    return {
      title: input.t('search.noResults'),
      description: input.t('search.noResultsFor', { q: input.q }),
    }
  }
  return {
    title: input.t('search.noResults'),
  }
}
