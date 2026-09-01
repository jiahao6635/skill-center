package com.iflytek.skillhub.search;

import java.util.Collection;
import java.util.List;

/**
 * Writes and removes documents in the search index implementation.
 */
public interface SearchIndexService {
    void index(SkillSearchDocument document);
    void batchIndex(List<SkillSearchDocument> documents);
    void remove(Long skillId);

    /**
     * Drops every indexed document whose skill id is not in {@code skillIds}.
     * An empty collection removes the entire index.
     */
    void retainOnly(Collection<Long> skillIds);
}
