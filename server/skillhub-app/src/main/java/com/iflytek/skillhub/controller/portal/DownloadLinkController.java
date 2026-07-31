package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.service.SkillDownloadLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Issues short-lived deep-link download URLs for QoderWork.
 *
 * <p>When a logged-in user clicks "Open in QoderWork", the frontend calls this
 * endpoint to obtain a 10-minute download URL. The URL either points at the
 * public redirect endpoint (production, presign-capable storage) or directly at
 * the streamed CLI download endpoint (dev/local storage).
 */
@RestController
@RequestMapping("/api/web/skills")
public class DownloadLinkController extends BaseApiController {

    private final SkillDownloadLinkService skillDownloadLinkService;

    public DownloadLinkController(ApiResponseFactory responseFactory,
                                  SkillDownloadLinkService skillDownloadLinkService) {
        super(responseFactory);
        this.skillDownloadLinkService = skillDownloadLinkService;
    }

    @PostMapping("/{namespace}/{slug}/download-link")
    public ApiResponse<DownloadLinkResponse> createDownloadLink(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestParam(required = false) String version,
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles,
            HttpServletRequest request) {

        SkillDownloadLinkService.IssueResult result = skillDownloadLinkService.issueDownloadLink(
                namespace, slug, version, principal.userId(),
                userNsRoles != null ? userNsRoles : Map.of());

        String baseUrl = buildBaseUrl(request);
        String downloadUrl = result.isRedirect()
                ? baseUrl + "/api/cli/v1/download-link/" + result.token()
                : baseUrl + result.fallbackPath();

        return ok("response.success.created",
                new DownloadLinkResponse(downloadUrl, result.expiresAt().toString()));
    }

    private String buildBaseUrl(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return proto + "://" + host;
    }

    public record DownloadLinkResponse(String downloadUrl, String expiresAt) {}
}
