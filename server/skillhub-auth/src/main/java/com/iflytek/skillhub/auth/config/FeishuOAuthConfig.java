package com.iflytek.skillhub.auth.config;

import com.iflytek.skillhub.auth.oauth.FeishuOAuthProperties;
import com.iflytek.skillhub.auth.oauth.FeishuOAuthService;
import com.iflytek.skillhub.auth.oauth.OAuthLoginFlowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers Feishu OAuth beans only when {@code skillhub.auth.feishu.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(prefix = "skillhub.auth.feishu", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FeishuOAuthProperties.class)
public class FeishuOAuthConfig {

    @Bean
    @ConditionalOnMissingBean
    public FeishuOAuthService feishuOAuthService(FeishuOAuthProperties properties,
                                                  OAuthLoginFlowService oauthLoginFlowService,
                                                  RestClient.Builder restClientBuilder) {
        return new FeishuOAuthService(properties, oauthLoginFlowService, restClientBuilder);
    }
}
