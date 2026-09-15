package com.iflytek.skillhub.domain.skill;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import java.util.Map;
import java.util.Objects;

/** Version authorization supplements container visibility; namespace managers never inherit private history. */
public final class VersionAccessPolicy {
    private VersionAccessPolicy() {}

    public static boolean isPrivate(Skill skill, SkillVersion version) {
        return skill.getVisibility() == SkillVisibility.PRIVATE
                || version.getDistributionVisibility() == SkillVisibility.PRIVATE;
    }

    public static boolean canRead(Skill skill, SkillVersion version, String userId,
                                  Map<Long, NamespaceRole> roles) {
        if (userId != null && Objects.equals(skill.getOwnerId(), userId)) return true;
        if (isPrivate(skill, version)) return false;
        NamespaceRole role = roles == null ? null : roles.get(skill.getNamespaceId());
        if (version.getDistributionVisibility() == SkillVisibility.NAMESPACE_ONLY && role == null) return false;
        return version.getStatus() == SkillVersionStatus.PUBLISHED
                || role == NamespaceRole.ADMIN || role == NamespaceRole.OWNER;
    }

    public static boolean isShared(SkillVersion version) {
        return version != null && version.getDistributionVisibility() != SkillVisibility.PRIVATE;
    }
}
