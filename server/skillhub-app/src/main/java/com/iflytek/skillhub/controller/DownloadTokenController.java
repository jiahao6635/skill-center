package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Issues short-lived API tokens for QoderWork deep link downloads.
 *
 * <p>When a user clicks "Open in QoderWork" the frontend calls this endpoint
 * to obtain a 10-minute download token. The token is embedded in the deep link
 * config so QoderWork can authenticate its download request.
 *
 * <p>Each invocation creates an independent token with a unique name suffix
 * (UUID) rather than rotating a single token. This avoids invalidating
 * previously issued tokens that QoderWork may still be holding, which caused
 * re-install / overwrite scenarios to fail with 401.
 */
@RestController
@RequestMapping("/api/web/auth")
public class DownloadTokenController extends BaseApiController {

    private static final String TOKEN_NAME_PREFIX = "Deep Link Download ";
    private static final String SCOPE_JSON = "[\"skill:read\"]";
    private static final long TOKEN_TTL_MINUTES = 10;

    private final ApiTokenService apiTokenService;

    public DownloadTokenController(ApiResponseFactory responseFactory,
                                   ApiTokenService apiTokenService) {
        super(responseFactory);
        this.apiTokenService = apiTokenService;
    }

    @PostMapping("/download-token")
    public ApiResponse<DownloadTokenResponse> createDownloadToken(
            @AuthenticationPrincipal PlatformPrincipal principal) {
        String expiresAt = Instant.now()
                .plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES)
                .toString();

        // Use a unique name per invocation so tokens don't revoke each other.
        // Expired tokens are auto-cleaned; short TTL (10 min) limits accumulation.
        String uniqueName = TOKEN_NAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        var result = apiTokenService.createToken(
                principal.userId(), uniqueName, SCOPE_JSON, expiresAt);

        return ok("response.success.created",
                new DownloadTokenResponse(result.rawToken(), expiresAt));
    }

    public record DownloadTokenResponse(String token, String expiresAt) {}
}
