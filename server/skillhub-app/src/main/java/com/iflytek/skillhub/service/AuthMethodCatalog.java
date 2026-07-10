package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.bootstrap.PassiveSessionAuthenticator;
import com.iflytek.skillhub.auth.direct.DirectAuthProvider;
import com.iflytek.skillhub.auth.oauth.FeishuOAuthProperties;
import com.iflytek.skillhub.auth.oauth.OAuthLoginRedirectSupport;
import com.iflytek.skillhub.config.AuthSessionBootstrapProperties;
import com.iflytek.skillhub.config.DirectAuthProperties;
import com.iflytek.skillhub.dto.AuthMethodResponse;
import com.iflytek.skillhub.dto.AuthProviderResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.stereotype.Service;

/**
 * Builds the catalog of authentication methods and OAuth providers that the UI
 * can render dynamically.
 */
@Service
public class AuthMethodCatalog {

    private final OAuth2ClientProperties oAuth2ClientProperties;
    private final DirectAuthProperties directAuthProperties;
    private final AuthSessionBootstrapProperties sessionBootstrapProperties;
    private final List<DirectAuthProvider> directAuthProviders;
    private final List<PassiveSessionAuthenticator> passiveSessionAuthenticators;
    private final ObjectProvider<FeishuOAuthProperties> feishuOAuthPropertiesProvider;

    public AuthMethodCatalog(OAuth2ClientProperties oAuth2ClientProperties,
                             DirectAuthProperties directAuthProperties,
                             AuthSessionBootstrapProperties sessionBootstrapProperties,
                             List<DirectAuthProvider> directAuthProviders,
                             List<PassiveSessionAuthenticator> passiveSessionAuthenticators,
                             ObjectProvider<FeishuOAuthProperties> feishuOAuthPropertiesProvider) {
        this.oAuth2ClientProperties = oAuth2ClientProperties;
        this.directAuthProperties = directAuthProperties;
        this.sessionBootstrapProperties = sessionBootstrapProperties;
        this.directAuthProviders = directAuthProviders;
        this.passiveSessionAuthenticators = passiveSessionAuthenticators;
        this.feishuOAuthPropertiesProvider = feishuOAuthPropertiesProvider;
    }

    public List<AuthProviderResponse> listOAuthProviders(String returnTo) {
        String sanitizedReturnTo = OAuthLoginRedirectSupport.sanitizeReturnTo(returnTo);
        List<AuthProviderResponse> providers = new ArrayList<>(oAuth2ClientProperties.getRegistration().entrySet().stream()
            .filter(entry -> !isPlaceholderClient(entry.getValue().getClientId()))
            .sorted(Comparator.comparing(entry -> entry.getKey()))
            .map(entry -> new AuthProviderResponse(
                entry.getKey(),
                entry.getValue().getClientName() != null && !entry.getValue().getClientName().isBlank()
                    ? entry.getValue().getClientName()
                    : entry.getKey(),
                buildAuthorizationUrl(entry.getKey(), sanitizedReturnTo)
            ))
            .toList());

        FeishuOAuthProperties feishuProps = feishuOAuthPropertiesProvider.getIfAvailable();
        if (feishuProps != null && feishuProps.isEnabled()) {
            providers.add(new AuthProviderResponse(
                "feishu",
                feishuProps.getDisplayName(),
                buildFeishuLoginUrl(sanitizedReturnTo)
            ));
        }

        return providers;
    }

    public List<AuthMethodResponse> listMethods(String returnTo) {
        String sanitizedReturnTo = OAuthLoginRedirectSupport.sanitizeReturnTo(returnTo);
        List<AuthMethodResponse> methods = new ArrayList<>();

        methods.add(new AuthMethodResponse(
            "local-password",
            "PASSWORD",
            "local",
            "Local Account",
            "/api/v1/auth/local/login"
        ));

        oAuth2ClientProperties.getRegistration().entrySet().stream()
            .filter(entry -> !isPlaceholderClient(entry.getValue().getClientId()))
            .sorted(Comparator.comparing(entry -> entry.getKey()))
            .forEach(entry -> methods.add(new AuthMethodResponse(
                "oauth-" + entry.getKey(),
                "OAUTH_REDIRECT",
                entry.getKey(),
                entry.getValue().getClientName() != null && !entry.getValue().getClientName().isBlank()
                    ? entry.getValue().getClientName()
                    : entry.getKey(),
                buildAuthorizationUrl(entry.getKey(), sanitizedReturnTo)
            )));

        FeishuOAuthProperties feishuProps = feishuOAuthPropertiesProvider.getIfAvailable();
        if (feishuProps != null && feishuProps.isEnabled()) {
            methods.add(new AuthMethodResponse(
                "oauth-feishu",
                "OAUTH_REDIRECT",
                "feishu",
                feishuProps.getDisplayName(),
                buildFeishuLoginUrl(sanitizedReturnTo)
            ));
        }

        if (directAuthProperties.isEnabled()) {
            directAuthProviders.stream()
                .sorted(Comparator.comparing(DirectAuthProvider::providerCode))
                .forEach(provider -> methods.add(new AuthMethodResponse(
                    "direct-" + provider.providerCode(),
                    "DIRECT_PASSWORD",
                    provider.providerCode(),
                    provider.displayName(),
                    "/api/v1/auth/direct/login"
                )));
        }

        if (sessionBootstrapProperties.isEnabled()) {
            passiveSessionAuthenticators.stream()
                .sorted(Comparator.comparing(PassiveSessionAuthenticator::providerCode))
                .forEach(provider -> methods.add(new AuthMethodResponse(
                    "bootstrap-" + provider.providerCode(),
                    "SESSION_BOOTSTRAP",
                    provider.providerCode(),
                    provider.displayName(),
                    "/api/v1/auth/session/bootstrap"
                )));
        }

        return methods;
    }

    private String buildAuthorizationUrl(String registrationId, String returnTo) {
        String baseUrl = "/oauth2/authorization/" + registrationId;
        if (returnTo == null) {
            return baseUrl;
        }
        return baseUrl + "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    private String buildFeishuLoginUrl(String returnTo) {
        String baseUrl = "/login/oauth2/feishu";
        if (returnTo == null) {
            return baseUrl;
        }
        return baseUrl + "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    /**
     * Returns true when the OAuth2 client ID is a known placeholder value,
     * meaning the provider has not been configured with real credentials.
     */
    private boolean isPlaceholderClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return true;
        }
        return "placeholder".equals(clientId) || "local-placeholder".equals(clientId);
    }
}
