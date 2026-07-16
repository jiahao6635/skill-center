package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.config.SkillPublishProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSkillClasspathPackageLoaderTest {
    @Test
    void loadsManageSkillhubFromExplicitClasspathIndex() {
        var loader = new BuiltinSkillClasspathPackageLoader(
                new DefaultResourceLoader(), new SkillPublishProperties());
        var source = new BuiltinSkillManifestLoader.ClasspathSource(
                "builtin-skills/packages/manage-skillhub/1.0.0/manage-skillhub",
                List.of("SKILL.md", "agents/openai.yaml", "references/cli-contract.md"));

        var entries = loader.load(source);

        assertThat(entries).extracting(entry -> entry.path())
                .containsExactly("SKILL.md", "agents/openai.yaml", "references/cli-contract.md");
        assertThat(entries.getFirst().content()).isNotEmpty();
    }
}
