package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.MessageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;

/**
 * Browser-side endpoint that lets an authenticated user authorize a pending
 * device code and records the operation in the audit log.
 */
@RestController
@RequestMapping("/api/v1/device")
public class DeviceAuthWebController extends BaseApiController {

    private final DeviceAuthService deviceAuthService;
    private final AuditLogService auditLogService;
    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;

    public DeviceAuthWebController(ApiResponseFactory responseFactory,
                                   DeviceAuthService deviceAuthService,
                                   AuditLogService auditLogService,
                                   NamespaceRepository namespaceRepository,
                                   NamespaceMemberRepository namespaceMemberRepository) {
        super(responseFactory);
        this.deviceAuthService = deviceAuthService;
        this.auditLogService = auditLogService;
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
    }

    @GetMapping("/request")
    public ApiResponse<DeviceRequestResponse> request(@RequestParam String userCode) {
        var data = deviceAuthService.inspectUserCode(userCode);
        return ok("response.success.read", new DeviceRequestResponse(data.getClientName(), data.getScopes(),
                data.getRequestedNamespaceSlug(), data.getExpiresInDays()));
    }

    @PostMapping("/authorize")
    public ApiResponse<MessageResponse> authorizeDevice(
        @RequestBody AuthorizeRequest request,
        @AuthenticationPrincipal PlatformPrincipal principal,
        HttpServletRequest httpRequest
    ) {
        if ("DENY".equalsIgnoreCase(request.decision())) {
            deviceAuthService.denyDeviceCode(request.userCode());
            return ok("response.success.updated", new MessageResponse("Device authorization denied"));
        }
        String namespaceSlug = request.namespaceSlug() != null ? request.namespaceSlug().replaceFirst("^@", "") : "";
        var namespace = namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException(
                        "error.namespace.slug.notFound", namespaceSlug));
        boolean superAdmin = principal.platformRoles().contains("SUPER_ADMIN");
        if (!superAdmin && namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), principal.userId()).isEmpty())
            throw new DomainForbiddenException("error.namespace.permission.denied");
        deviceAuthService.authorizeDeviceCode(request.userCode(), principal.userId(), namespace.getId());
        auditLogService.record(
            principal.userId(),
            "DEVICE_AUTHORIZE",
            "DEVICE_CODE",
            null,
            MDC.get("requestId"),
            httpRequest.getRemoteAddr(),
            httpRequest.getHeader("User-Agent"),
            "{\"clientName\":\"device-flow\",\"namespaceId\":" + namespace.getId() + "}"
        );
        return ok("response.success.updated", new MessageResponse("Device authorized successfully"));
    }

    public record AuthorizeRequest(String userCode, String namespaceSlug, String decision) {}
    public record DeviceRequestResponse(String clientName, java.util.List<String> scopes,
                                        String requestedNamespaceSlug, int expiresInDays) {}
}
