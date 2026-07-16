package com.iflytek.skillhub.config;

import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPublishPropertiesProtocolTest {
    @Test
    void runtimeDefaultMatchesProtocolFileLimit() {
        assertThat(new SkillPublishProperties().getMaxFileCount())
                .isEqualTo(SkillPackagePolicy.MAX_FILE_COUNT)
                .isEqualTo(500);
    }
}
