package com.iflytek.skillhub.domain.namespace;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures newly active users belong to the built-in global and private namespaces.
 */
@Service
public class GlobalNamespaceMembershipService {

    private static final String GLOBAL_NAMESPACE_SLUG = "global";
    private static final String PRIVATE_NAMESPACE_SLUG = "private";

    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;

    public GlobalNamespaceMembershipService(NamespaceRepository namespaceRepository,
                                            NamespaceMemberRepository namespaceMemberRepository) {
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
    }

    @Transactional
    public void ensureMember(String userId) {
        ensureNamespaceMembership(GLOBAL_NAMESPACE_SLUG, userId);
        ensureNamespaceMembership(PRIVATE_NAMESPACE_SLUG, userId);
    }

    private void ensureNamespaceMembership(String namespaceSlug, String userId) {
        Namespace namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new IllegalStateException("Missing built-in namespace: " + namespaceSlug));

        namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), userId)
                .orElseGet(() -> namespaceMemberRepository.save(
                        new NamespaceMember(namespace.getId(), userId, NamespaceRole.MEMBER)
                ));
    }
}
