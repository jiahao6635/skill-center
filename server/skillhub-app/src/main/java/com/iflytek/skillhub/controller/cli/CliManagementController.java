package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.AdminSkillActionRequest;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.ConfirmPublishRequest;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.ReviewActionRequest;
import com.iflytek.skillhub.dto.ReviewTaskResponse;
import com.iflytek.skillhub.dto.SkillLifecycleMutationResponse;
import com.iflytek.skillhub.dto.SubmitReviewRequest;
import com.iflytek.skillhub.dto.SkillVersionRereleaseRequest;
import com.iflytek.skillhub.dto.cli.CliReviewTaskResponse;
import com.iflytek.skillhub.dto.cli.CliManagedSkillResponse;
import com.iflytek.skillhub.dto.SkillLifecycleVersionResponse;
import com.iflytek.skillhub.dto.SkillVersionResponse;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.domain.skill.service.SkillLifecycleProjectionService;
import org.springframework.data.domain.PageRequest;
import com.iflytek.skillhub.security.RequestAuthorizationContext;
import com.iflytek.skillhub.security.TokenGrantGuard;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GovernanceWorkflowAppService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/cli/v1/manage")
public class CliManagementController extends BaseApiController {
    private final GovernanceWorkflowAppService workflow;
    private final NamespaceRepository namespaceRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final TokenGrantGuard tokenGrantGuard;
    private final SkillQueryService skillQueryService;

    public CliManagementController(ApiResponseFactory responseFactory, GovernanceWorkflowAppService workflow,
            NamespaceRepository namespaceRepository, ReviewTaskRepository reviewTaskRepository,
            TokenGrantGuard tokenGrantGuard, SkillQueryService skillQueryService) {
        super(responseFactory);
        this.workflow = workflow;
        this.namespaceRepository = namespaceRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.tokenGrantGuard = tokenGrantGuard;
        this.skillQueryService = skillQueryService;
    }

    @GetMapping("/skills/{namespace}/{slug}")
    public ApiResponse<CliManagedSkillResponse> skill(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        guardNamespace(namespace, "skill:read", auth);
        var detail = skillQueryService.getSkillDetail(
                namespace, slug, auth.userId(), nsRoles != null ? nsRoles : Map.of());
        return ok("response.success.read", new CliManagedSkillResponse(
                detail.id(), namespace, detail.slug(), detail.displayName(), detail.visibility(),
                detail.status(), detail.ownerId(), lifecycle(detail.headlineVersion()),
                lifecycle(detail.publishedVersion()), lifecycle(detail.ownerPreviewVersion()),
                availableActions(detail.canManageLifecycle(), detail.ownerPreviewVersion() != null)));
    }

    @GetMapping("/skills/{namespace}/{slug}/versions")
    public ApiResponse<PageResponse<SkillVersionResponse>> versions(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        guardNamespace(namespace, "skill:read", auth);
        var versions = skillQueryService.listVersions(
                namespace, slug, auth.userId(), nsRoles != null ? nsRoles : Map.of(),
                PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100))));
        return ok("response.success.read", PageResponse.from(versions.map(version -> new SkillVersionResponse(
                version.getId(), version.getVersion(), version.getStatus().name(), version.getChangelog(),
                version.getFileCount(), version.getTotalSize(), version.getPublishedAt(),
                skillQueryService.isDownloadAvailable(version)))));
    }

    @PostMapping("/skills/{namespace}/{slug}/archive")
    public ApiResponse<SkillLifecycleMutationResponse> archive(@PathVariable String namespace, @PathVariable String slug,
            @RequestBody(required = false) AdminSkillActionRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.updated", workflow.archiveSkill(namespace, slug, request, auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @PostMapping("/skills/{namespace}/{slug}/unarchive")
    public ApiResponse<SkillLifecycleMutationResponse> unarchive(@PathVariable String namespace, @PathVariable String slug,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.updated", workflow.unarchiveSkill(namespace, slug, auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @PostMapping("/skills/{namespace}/{slug}/versions/{version}/withdraw-review")
    public ApiResponse<SkillLifecycleMutationResponse> withdraw(@PathVariable String namespace, @PathVariable String slug, @PathVariable String version,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.updated", workflow.withdrawReviewVersion(namespace, slug, version, auth.userId(), AuditRequestContext.from(http)));
    }

    @PostMapping("/skills/{namespace}/{slug}/versions/{version}/rerelease")
    public ApiResponse<SkillLifecycleMutationResponse> rerelease(@PathVariable String namespace, @PathVariable String slug, @PathVariable String version,
            @RequestBody SkillVersionRereleaseRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:publish", auth);
        return ok("response.success.updated", workflow.rereleaseVersion(namespace, slug, version, request, auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @DeleteMapping("/skills/{namespace}/{slug}/versions/{version}")
    public ApiResponse<SkillLifecycleMutationResponse> deleteVersion(@PathVariable String namespace, @PathVariable String slug, @PathVariable String version,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.deleted", workflow.deleteVersion(namespace, slug, version, auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @PostMapping("/skills/{namespace}/{slug}/submit-review")
    public ApiResponse<SkillLifecycleMutationResponse> submitReview(@PathVariable String namespace, @PathVariable String slug,
            @RequestBody SubmitReviewRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.updated", workflow.submitForReview(namespace, slug, request.version(), request.targetVisibility(), auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @PostMapping("/skills/{namespace}/{slug}/confirm-publish")
    public ApiResponse<SkillLifecycleMutationResponse> confirmPublish(@PathVariable String namespace, @PathVariable String slug,
            @RequestBody ConfirmPublishRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        guardNamespace(namespace, "skill:lifecycle", auth);
        return ok("response.success.updated", workflow.confirmPublish(namespace, slug, request.version(), auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @GetMapping("/reviews")
    public ApiResponse<PageResponse<ReviewTaskResponse>> reviews(@RequestParam(defaultValue = "PENDING") String status,
            @RequestParam String namespace, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        Long namespaceId = namespaceId(namespace);
        tokenGrantGuard.requireAgent(auth, "review:read", namespaceId);
        return ok("response.success.read", workflow.listReviews(status, namespaceId, page, size, "DESC", auth.userId(), nsRoles));
    }

    @GetMapping("/reviews/{id}")
    public ApiResponse<CliReviewTaskResponse> review(@PathVariable Long id, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        ReviewTask task = guardReview(id, "review:read", auth);
        return ok("response.success.read", CliReviewTaskResponse.from(
                workflow.getReviewDetail(id, auth.userId(), nsRoles), task.getVersion()));
    }

    @GetMapping("/reviews/submissions")
    public ApiResponse<PageResponse<ReviewTaskResponse>> submissions(
            @RequestParam String namespace,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth) {
        Long namespaceId = namespaceId(namespace);
        tokenGrantGuard.requireAgent(auth, "review:read", namespaceId);
        return ok("response.success.read",
                workflow.listMyReviewSubmissions(page, size, auth.userId(), namespaceId));
    }

    @GetMapping("/reviews/{id}/download")
    public ResponseEntity<InputStreamResource> downloadReview(
            @PathVariable Long id,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        guardReview(id, "review:read", auth);
        var result = workflow.downloadReviewPackage(id, auth.userId(), nsRoles);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.contentLength())
                .body(new InputStreamResource(result.openContent()));
    }

    @PostMapping("/reviews/{id}/approve")
    public ApiResponse<ReviewTaskResponse> approve(@PathVariable Long id, @RequestBody(required = false) ReviewActionRequest request,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        ReviewTask task = guardReview(id, "review:decide", auth);
        requireTaskVersion(request, task);
        return ok("response.success.updated", workflow.approveReview(id, request != null ? request.comment() : null, auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    @PostMapping("/reviews/{id}/reject")
    public ApiResponse<ReviewTaskResponse> reject(@PathVariable Long id, @RequestBody ReviewActionRequest request,
            @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles, HttpServletRequest http) {
        ReviewTask task = guardReview(id, "review:decide", auth);
        requireTaskVersion(request, task);
        if (request == null || request.comment() == null || request.comment().isBlank() || request.comment().length() > 500)
            throw new com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException("review.reject.comment.required");
        return ok("response.success.updated", workflow.rejectReview(id, request.comment(), auth.userId(), nsRoles, AuditRequestContext.from(http)));
    }

    private void guardNamespace(String slug, String scope, RequestAuthorizationContext auth) {
        tokenGrantGuard.requireAgent(auth, scope, namespaceId(slug));
    }
    private ReviewTask guardReview(Long id, String scope, RequestAuthorizationContext auth) {
        var task = reviewTaskRepository.findById(id).orElseThrow(() -> new DomainNotFoundException("review_task.not_found", id));
        tokenGrantGuard.requireAgent(auth, scope, task.getNamespaceId());
        return task;
    }
    private void requireTaskVersion(ReviewActionRequest request, ReviewTask task) {
        if (request == null || request.version() == null || !request.version().equals(task.getVersion())) {
            throw new com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException(
                    "review.task.version.conflict", task.getVersion());
        }
    }
    private Long namespaceId(String slug) {
        String clean = slug.startsWith("@") ? slug.substring(1) : slug;
        return namespaceRepository.findBySlug(clean).orElseThrow(() -> new DomainNotFoundException("namespace.not_found", clean)).getId();
    }

    private SkillLifecycleVersionResponse lifecycle(SkillLifecycleProjectionService.VersionProjection pointer) {
        return pointer == null ? null
                : new SkillLifecycleVersionResponse(pointer.id(), pointer.version(), pointer.status());
    }

    private java.util.List<String> availableActions(boolean canManage, boolean hasPreview) {
        if (!canManage) return java.util.List.of();
        java.util.List<String> actions = new java.util.ArrayList<>(
                java.util.List.of("archive", "unarchive", "delete-version", "rerelease"));
        if (hasPreview) actions.addAll(java.util.List.of("submit-review", "withdraw-review", "confirm-publish"));
        return java.util.List.copyOf(actions);
    }
}
