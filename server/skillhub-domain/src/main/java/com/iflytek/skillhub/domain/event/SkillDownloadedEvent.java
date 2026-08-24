package com.iflytek.skillhub.domain.event;

import com.iflytek.skillhub.domain.usage.SkillUsageActorKind;
import com.iflytek.skillhub.domain.usage.SkillUsageRequestContext;

/**
 * Published after the download counters were incremented for a PUBLISHED
 * version. {@code delivery} is the domain/storage outcome (presigned | bundle |
 * deeplink), never the HTTP 302-vs-stream decision, which is made later by the
 * controller. The two-arg constructor keeps the legacy internal/test shape;
 * events built through it are not recordable and the usage listener skips them.
 */
public record SkillDownloadedEvent(
        Long skillId,
        Long versionId,
        String version,
        String actorUserId,
        String actorKey,
        SkillUsageActorKind actorKind,
        String namespaceSlug,
        String skillSlug,
        String delivery,
        SkillUsageRequestContext requestContext
) {
    public SkillDownloadedEvent(Long skillId, Long versionId) {
        this(skillId, versionId, null, null, null, null, null, null, null, null);
    }
}
