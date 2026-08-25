package com.iflytek.skillhub.search;

import java.util.List;

/**
 * Immutable search request model shared between application code and search implementations.
 */
public record SearchQuery(
        String keyword,
        Long namespaceId,
        SearchVisibilityScope visibilityScope,
        String sortBy,
        int page,
        int size,
        List<String> labelSlugs,
        boolean requireInstallableLatest,
        List<String> ownerIds
) {
    /**
     * {@code ownerIds == null} means no owner filter. An empty list means match nothing
     * (the query service short-circuits to an empty page and must not emit {@code IN ()}).
     */
    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs,
            boolean requireInstallableLatest) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs, requireInstallableLatest, null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs, false, null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, List.of(), false, null);
    }
}
