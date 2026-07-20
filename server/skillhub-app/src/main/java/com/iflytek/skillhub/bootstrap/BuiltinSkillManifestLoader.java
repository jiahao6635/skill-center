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
            if (!StringUtils.hasText(slug) || !StringUtils.hasText(version)) {
                log.warn("Skipping built-in skill manifest item {} because slug and version are required", index);
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

            // Support two source formats:
            // 1. URL-based: { "slug": "...", "version": "...", "url": "https://..." }
            // 2. Classpath-files: { "slug": "...", "version": "...", "source": { "type": "classpath-files", "basePath": "...", "files": [...] } }
            JsonNode sourceNode = itemNode.get("source");
            if (sourceNode != null && sourceNode.isObject()) {
                String type = text(sourceNode, "type");
                if ("classpath-files".equals(type)) {
                    String basePath = text(sourceNode, "basePath");
                    List<String> files = textList(sourceNode, "files");
                    if (!StringUtils.hasText(basePath) || files.isEmpty()) {
                        log.warn("Skipping built-in skill manifest item {} because classpath-files source requires basePath and files", index);
                        continue;
                    }
                    items.add(new ManifestItem(slug, version, null, basePath, files));
                    continue;
                }
            }

            String url = text(itemNode, "url");
            if (!StringUtils.hasText(url)) {
                log.warn("Skipping built-in skill manifest item {} because either url or source is required", index);
                continue;
            }
            items.add(new ManifestItem(slug, version, url, null, null));
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

    private static List<String> textList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (element.isTextual() && StringUtils.hasText(element.asText())) {
                result.add(element.asText().trim());
            }
        }
        return List.copyOf(result);
    }

    public record ManifestItem(String slug, String version, String url, String basePath, List<String> files) {
        public boolean isClasspathSource() {
            return basePath != null;
        }
    }
}
