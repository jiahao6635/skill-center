package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.SkillDownloadLinkService;
import com.iflytek.skillhub.usage.UsageContextFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public redirect endpoint consumed by QoderWork during deep-link install.
 *
 * <p>QoderWork GETs this URL (no authentication required); the backend records
 * the download metric and responds with a 302 redirect to the presigned
 * object-storage URL. The token is unguessable, so possession of the URL is the
 * only capability needed — safe even for private skills.
 *
 * <p>The recorded download is attributed to the link ISSUER stored with the
 * token; the fetcher's context here only supplies requestId/ip/ua for admin
 * triage (and the anonymous fallback key for legacy tokens).
 *
 * <p>Unknown or expired tokens yield a 404 via {@code DomainNotFoundException}.
 */
@RestController
@RequestMapping("/api/cli/v1/download-link")
public class CliDownloadLinkController {

    private final SkillDownloadLinkService skillDownloadLinkService;
    private final UsageContextFactory usageContextFactory;

    public CliDownloadLinkController(SkillDownloadLinkService skillDownloadLinkService,
                                     UsageContextFactory usageContextFactory) {
        this.skillDownloadLinkService = skillDownloadLinkService;
        this.usageContextFactory = usageContextFactory;
    }

    @GetMapping("/{token}")
    @RateLimit(category = "download", authenticated = 120, anonymous = 30)
    public ResponseEntity<Void> redirect(@PathVariable String token, HttpServletRequest request) {
        String presignedUrl = skillDownloadLinkService.resolveForRedirect(
                token, usageContextFactory.fromRequest(request, null));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, presignedUrl)
                .build();
    }
}
