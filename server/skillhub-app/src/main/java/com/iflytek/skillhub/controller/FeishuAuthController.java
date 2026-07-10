package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.oauth.AccountDisabledException;
import com.iflytek.skillhub.auth.oauth.AccountPendingException;
import com.iflytek.skillhub.auth.oauth.FeishuOAuthService;
import com.iflytek.skillhub.auth.oauth.OAuthLoginFlowService;
import com.iflytek.skillhub.auth.oauth.OAuthLoginRedirectSupport;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.session.PlatformSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * HTTP controller for the Feishu (Lark) QR-code OAuth login flow.
 *
 * <p>Exposes two endpoints:
 * <ul>
 *   <li>{@code GET /login/oauth2/feishu} — redirects the user to the Feishu authorization page</li>
 *   <li>{@code GET /login/oauth2/feishu/callback} — handles the OAuth callback and establishes a session</li>
 * </ul>
 *
 * <p>The controller is always registered, but delegates to {@link FeishuOAuthService}
 * which is only available when Feishu login is enabled.
 */
@Controller
@RequestMapping("/login/oauth2/feishu")
public class FeishuAuthController {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthController.class);

    private final ObjectProvider<FeishuOAuthService> feishuOAuthServiceProvider;
    private final PlatformSessionService platformSessionService;
    private final OAuthLoginFlowService oauthLoginFlowService;

    public FeishuAuthController(ObjectProvider<FeishuOAuthService> feishuOAuthServiceProvider,
                                 PlatformSessionService platformSessionService,
                                 OAuthLoginFlowService oauthLoginFlowService) {
        this.feishuOAuthServiceProvider = feishuOAuthServiceProvider;
        this.platformSessionService = platformSessionService;
        this.oauthLoginFlowService = oauthLoginFlowService;
    }

    /**
     * Initiates the Feishu OAuth flow by redirecting to the Feishu authorization page.
     *
     * @param returnTo the path to return to after successful login (optional)
     */
    @GetMapping
    public void initiate(@RequestParam(name = "returnTo", required = false) String returnTo,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        FeishuOAuthService feishuOAuthService = feishuOAuthServiceProvider.getIfAvailable();
        if (feishuOAuthService == null) {
            log.warn("Feishu OAuth login attempted but Feishu is not enabled");
            response.sendRedirect("/login");
            return;
        }

        // Store returnTo in the request parameter so FeishuOAuthService.buildAuthorizationUrl
        // can pick it up via oauthLoginFlowService.rememberReturnTo(request).
        String authorizationUrl = feishuOAuthService.buildAuthorizationUrl(request);
        response.sendRedirect(authorizationUrl);
    }

    /**
     * Handles the Feishu OAuth callback, exchanges the authorization code for tokens,
     * provisions the user, and establishes a session.
     *
     * @param code  the authorization code from Feishu
     * @param state the CSRF state parameter from Feishu
     */
    @GetMapping("/callback")
    public void callback(@RequestParam(name = "code", required = false) String code,
                         @RequestParam(name = "state", required = false) String state,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        FeishuOAuthService feishuOAuthService = feishuOAuthServiceProvider.getIfAvailable();
        if (feishuOAuthService == null) {
            response.sendRedirect("/login");
            return;
        }

        if (code == null || code.isBlank()) {
            log.warn("Feishu OAuth callback received without authorization code");
            response.sendRedirect("/login?error=feishu_no_code");
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("Feishu OAuth callback received without an active session");
            response.sendRedirect("/login?error=feishu_session_expired");
            return;
        }

        try {
            PlatformPrincipal principal = feishuOAuthService.handleCallback(code, state, session);
            platformSessionService.establishSession(principal, request);

            String redirectTarget = oauthLoginFlowService.consumeReturnTo(request.getSession(false));
            if (redirectTarget == null) {
                redirectTarget = OAuthLoginRedirectSupport.DEFAULT_TARGET_URL;
            }
            response.sendRedirect(redirectTarget);

        } catch (AccountPendingException e) {
            response.sendRedirect("/pending-approval");
        } catch (AccountDisabledException e) {
            response.sendRedirect("/login?reason=accountDisabled");
        } catch (OAuth2AuthenticationException e) {
            String errorCode = e.getError().getErrorCode();
            if ("access_denied".equals(errorCode)) {
                response.sendRedirect("/access-denied");
            } else if ("invalid_state".equals(errorCode)) {
                log.warn("Feishu OAuth state mismatch");
                response.sendRedirect("/login?error=feishu_state_mismatch");
            } else {
                log.error("Feishu OAuth authentication failed: {} - {}",
                    errorCode, e.getError().getDescription());
                response.sendRedirect("/login?error=feishu_error");
            }
        } catch (Exception e) {
            log.error("Unexpected error during Feishu OAuth callback", e);
            response.sendRedirect("/login?error=feishu_error");
        }
    }
}
