package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ReviewTaskResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GovernanceWorkflowAppService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applies a review decision that originated from a Feishu card action.
 *
 * <p>Reverse-maps the acting Feishu {@code open_id} to a SkillHub user, rebuilds
 * that user's namespace-role context (the same map an authenticated request
 * would carry), and routes through {@link GovernanceWorkflowAppService} so RBAC
 * permission checks and audit logging run exactly as in the Web flow. The
 * underlying {@code ReviewService} optimistic lock guarantees a single decision
 * even if Web and Feishu race.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class ReviewFeishuActionService {

    private static final String FEISHU_PROVIDER = "feishu";

    private final GovernanceWorkflowAppService governanceWorkflowAppService;
    private final IdentityBindingRepository identityBindingRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;

    public ReviewFeishuActionService(GovernanceWorkflowAppService governanceWorkflowAppService,
                                     IdentityBindingRepository identityBindingRepository,
                                     NamespaceMemberRepository namespaceMemberRepository) {
        this.governanceWorkflowAppService = governanceWorkflowAppService;
        this.identityBindingRepository = identityBindingRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
    }

    /** Resolves the SkillHub user bound to a Feishu {@code open_id}. */
    public Optional<String> resolveUserId(String openId) {
        return identityBindingRepository.findByProviderCodeAndSubject(FEISHU_PROVIDER, openId)
                .map(IdentityBinding::getUserId);
    }

    public ReviewTaskResponse approve(Long reviewTaskId, String userId, AuditRequestContext auditContext) {
        return governanceWorkflowAppService.approveReview(
                reviewTaskId, null, userId, namespaceRoles(userId), auditContext);
    }

    public ReviewTaskResponse reject(Long reviewTaskId, String userId, String reason,
                                     AuditRequestContext auditContext) {
        return governanceWorkflowAppService.rejectReview(
                reviewTaskId, reason, userId, namespaceRoles(userId), auditContext);
    }

    /**
     * Rebuilds the user's namespace-role map, mirroring {@code AuthContextFilter}
     * which builds the {@code userNsRoles} request attribute for Web requests.
     */
    private Map<Long, NamespaceRole> namespaceRoles(String userId) {
        return namespaceMemberRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        NamespaceMember::getNamespaceId,
                        NamespaceMember::getRole,
                        (left, right) -> left));
    }
}
