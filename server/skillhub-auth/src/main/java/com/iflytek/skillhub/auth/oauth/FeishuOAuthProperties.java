package com.iflytek.skillhub.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Feishu (Lark) OAuth2 login integration.
 *
 * <p>Feishu uses a non-standard OAuth2 flow that requires an intermediate
 * {@code app_access_token} and uses {@code app_id} instead of {@code client_id}.
 * This properties class is activated conditionally when Feishu login is enabled.
 */
@ConfigurationProperties(prefix = "skillhub.auth.feishu")
public class FeishuOAuthProperties {

    private boolean enabled = false;

    /** Feishu app ID from the developer console. */
    private String appId;

    /** Feishu app secret from the developer console. */
    private String appSecret;

    /** Display name shown on the login page. */
    private String displayName = "飞书";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
