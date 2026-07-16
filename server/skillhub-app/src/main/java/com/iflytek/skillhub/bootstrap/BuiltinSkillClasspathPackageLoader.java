package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.bootstrap.BuiltinSkillManifestLoader.ClasspathSource;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class BuiltinSkillClasspathPackageLoader {
    private final ResourceLoader resourceLoader;
    private final SkillPublishProperties properties;

    public BuiltinSkillClasspathPackageLoader(ResourceLoader resourceLoader, SkillPublishProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    public List<PackageEntry> load(ClasspathSource source) {
        if (source.files().size() > properties.getMaxFileCount())
            throw new IllegalArgumentException("Built-in skill has too many files");
        List<PackageEntry> entries = new ArrayList<>();
        long total = 0;
        for (String path : source.files()) {
            Resource resource = resourceLoader.getResource("classpath:" + source.basePath() + "/" + path);
            if (!resource.exists()) throw new IllegalArgumentException("Built-in skill resource is missing: " + path);
            try (var inputStream = resource.getInputStream()) {
                byte[] content = inputStream.readAllBytes();
                if (content.length > properties.getMaxSingleFileSize())
                    throw new IllegalArgumentException("Built-in skill file is too large: " + path);
                total += content.length;
                if (total > properties.getMaxPackageSize())
                    throw new IllegalArgumentException("Built-in skill package is too large");
                String contentType = URLConnection.guessContentTypeFromName(path);
                entries.add(new PackageEntry(path, content, content.length,
                        contentType != null ? contentType : "application/octet-stream"));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to read built-in skill resource: " + path, exception);
            }
        }
        return List.copyOf(entries);
    }
}
