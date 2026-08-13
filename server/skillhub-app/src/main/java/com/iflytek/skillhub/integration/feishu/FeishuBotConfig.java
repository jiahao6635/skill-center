package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers the outbound Feishu bot client only when
 * {@code skillhub.integration.feishu.enabled=true}.
 *
 * <p>When disabled, no {@link FeishuBotClient} bean exists and the review
 * listeners (which depend on {@code ObjectProvider<FeishuBotClient>}) simply skip
 * Feishu delivery, degrading gracefully to in-app notifications.
 */
@Configuration
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FeishuBotProperties.class)
public class FeishuBotConfig {

    @Bean
    @ConditionalOnMissingBean
    public FeishuBotClient feishuBotClient(FeishuBotProperties properties,
                                           RestClient.Builder restClientBuilder,
                                           ObjectMapper objectMapper) {
        return new FeishuBotClient(properties, restClientBuilder, objectMapper);
    }
}
