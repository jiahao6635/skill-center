package com.iflytek.skillhub.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.io.Serializable;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Redis values written through the shared template carry type
 * information and round-trip into their original classes. Without default
 * typing, Jackson deserializes every JSON object into a {@code LinkedHashMap},
 * which broke download-link token lookups (404) and device-flow code casts
 * (500).
 */
class RedisTemplateConfigTest {

    public static class SamplePayload implements Serializable {
        private String url;
        private Long id;
        private boolean flag;

        public SamplePayload() {}

        public SamplePayload(String url, Long id, boolean flag) {
            this.url = url;
            this.id = id;
            this.flag = flag;
        }

        public String getUrl() { return url; }
        public Long getId() { return id; }
        public boolean isFlag() { return flag; }
    }

    @Test
    void redisValueObjectMapper_PreservesConcreteTypeOnRoundTrip() throws Exception {
        ObjectMapper mapper = redisValueObjectMapper(new ObjectMapper());
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        byte[] bytes = serializer.serialize(new SamplePayload("https://example.com/x", 42L, true));

        assertThat(new String(bytes)).contains("@class");

        Object restored = serializer.deserialize(bytes);
        assertThat(restored).isInstanceOf(SamplePayload.class);
        SamplePayload payload = (SamplePayload) restored;
        assertThat(payload.getUrl()).isEqualTo("https://example.com/x");
        assertThat(payload.getId()).isEqualTo(42L);
        assertThat(payload.isFlag()).isTrue();
    }

    @Test
    void redisValueObjectMapper_RoundTripsFinalScalars() throws Exception {
        ObjectMapper mapper = redisValueObjectMapper(new ObjectMapper());
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        assertThat(serializer.deserialize(serializer.serialize("plain-string"))).isEqualTo("plain-string");
        assertThat(serializer.deserialize(serializer.serialize(Boolean.TRUE))).isEqualTo(Boolean.TRUE);
    }

    @Test
    void redisValueObjectMapper_DoesNotMutateSharedMapper() throws Exception {
        ObjectMapper shared = new ObjectMapper();
        redisValueObjectMapper(shared);

        String json = shared.writeValueAsString(new SamplePayload("u", 1L, false));
        assertThat(json).doesNotContain("@class");
    }

    private static ObjectMapper redisValueObjectMapper(ObjectMapper objectMapper) throws Exception {
        Method method = RedisTemplateConfig.class.getDeclaredMethod("redisValueObjectMapper", ObjectMapper.class);
        method.setAccessible(true);
        return (ObjectMapper) method.invoke(null, objectMapper);
    }
}
