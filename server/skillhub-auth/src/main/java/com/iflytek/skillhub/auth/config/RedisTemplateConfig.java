package com.iflytek.skillhub.auth.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Provides the shared Redis template used by authentication and other cross-cutting services.
 */
@Configuration
public class RedisTemplateConfig {

    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(redisValueObjectMapper(objectMapper));

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Copies the shared ObjectMapper and enables default typing so that
     * values written through this template carry their concrete type
     * ({@code @class}) and can be deserialized back into the original domain
     * classes (for example download-link payloads or device-flow codes).
     *
     * <p>Without typing, Jackson deserializes every JSON object into a
     * {@code LinkedHashMap}, which breaks {@code instanceof} checks and casts
     * on read and surfaces as missing tokens or 500 errors.
     */
    private static ObjectMapper redisValueObjectMapper(ObjectMapper objectMapper) {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("java.")
                .allowIfSubType("org.springframework.")
                .allowIfSubType("com.iflytek.skillhub.")
                .build();
        return objectMapper.copy()
                .activateDefaultTyping(validator,
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY);
    }
}
