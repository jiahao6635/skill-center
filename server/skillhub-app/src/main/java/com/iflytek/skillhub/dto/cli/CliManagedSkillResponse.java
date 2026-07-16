package com.iflytek.skillhub.dto.cli;

import com.iflytek.skillhub.dto.SkillLifecycleVersionResponse;
import java.util.List;

public record CliManagedSkillResponse(
        Long id,
        String namespace,
        String slug,
        String displayName,
        String visibility,
        String status,
        String ownerId,
        SkillLifecycleVersionResponse headlineVersion,
        SkillLifecycleVersionResponse publishedVersion,
        SkillLifecycleVersionResponse ownerPreviewVersion,
        List<String> availableActions) {}
