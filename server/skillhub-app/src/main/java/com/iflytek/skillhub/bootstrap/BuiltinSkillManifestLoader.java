package com.iflytek.skillhub.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class BuiltinSkillManifestLoader {

    static final String MANIFEST_LOCATION = "classpath:builtin-skills/manifest.json";
    static final int MAX_ITEMS = 100;

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillManifestLoader.class);

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public BuiltinSkillManifestLoader(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    public List<ManifestItem> load() {
        Resource resource = resourceLoader.getResource(MANIFEST_LOCATION);
        if (!resource.exists()) {
            log.warn("Built-in skill manifest not found at {}", MANIFEST_LOCATION);
            return List.of();
        }

        JsonNode root;
        try (InputStream inputStream = resource.getInputStream()) {
            root = objectMapper.readTree(inputStream);
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to read built-in skill manifest at {}: {}", MANIFEST_LOCATION, ex.getMessage());
            return List.of();
        }

        if (root == null || root.isNull()) {
            log.warn("Built-in skill manifest at {} is empty", MANIFEST_LOCATION);
            return List.of();
        }

        JsonNode skillsNode = root.path("skills");
        if (!skillsNode.isArray()) {
            log.warn("Built-in skill manifest at {} does not contain an array field 'skills'", MANIFEST_LOCATION);
            return List.of();
        }

        List<ManifestItem> items = new ArrayList<>();
        Set<String> seenSlugVersions = new HashSet<>();
        int totalEntries = skillsNode.size();
        if (totalEntries > MAX_ITEMS) {
            log.warn("Built-in skill manifest has {} entries, only the first {} entries will be processed",
                    totalEntries, MAX_ITEMS);
        }
        int limit = Math.min(totalEntries, MAX_ITEMS);
        for (int index = 0; index < limit; index++) {
            JsonNode itemNode = skillsNode.get(index);
            String slug = text(itemNode, "slug");
            String version = text(itemNode, "version");
            String url = text(itemNode, "url");
            JsonNode sourceNode = itemNode.path("source");
            ClasspathSource source = parseClasspathSource(sourceNode);
            if (!StringUtils.hasText(slug) || !StringUtils.hasText(version)
                    || (StringUtils.hasText(url) == (source != null))) {
                log.warn("Skipping built-in skill manifest item {} because exactly one package source is required", index);
                continue;
            }
            try {
                SlugValidator.validate(slug);
            } catch (RuntimeException ex) {
                log.warn("Skipping built-in skill manifest item {} because slug is invalid [slug={}]: {}",
                        index, slug, ex.getMessage());
                continue;
            }

            String key = slug + "\n" + version;
            if (!seenSlugVersions.add(key)) {
                log.warn("Skipping duplicate built-in skill manifest item for slug={} version={}", slug, version);
                continue;
            }

            items.add(new ManifestItem(slug, version, url, source));
        }
        return List.copyOf(items);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            return "";
        }
        return value.asText().trim();
    }

    private ClasspathSource parseClasspathSource(JsonNode node) {
        if (!node.isObject() || !"classpath-files".equals(text(node, "type"))) return null;
        String basePath = text(node, "basePath");
        if (!basePath.startsWith("builtin-skills/packages/")) return null;
        try {
            if (!basePath.equals(com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy
                    .normalizeEntryPath(basePath))) return null;
        } catch (RuntimeException exception) {
            return null;
        }
        JsonNode filesNode = node.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) return null;
        List<String> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode file : filesNode) {
            if (!file.isTextual()) return null;
            String path = file.asText().trim();
            try {
                path = com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy.normalizeEntryPath(path);
            } catch (RuntimeException exception) {
                return null;
            }
            if (!seen.add(path)) return null;
            files.add(path);
        }
        return new ClasspathSource(basePath, List.copyOf(files));
    }

    public record ManifestItem(String slug, String version, String url, ClasspathSource classpathSource) {
        public ManifestItem(String slug, String version, String url) { this(slug, version, url, null); }
    }

    public record ClasspathSource(String basePath, List<String> files) {}
}
