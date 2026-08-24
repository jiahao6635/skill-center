package com.iflytek.skillhub.domain.usage;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link SkillUsageActor}: (skill_id, action, actor_key).
 */
public class SkillUsageActorId implements Serializable {

    private Long skillId;
    private String action;
    private String actorKey;

    public SkillUsageActorId() {}

    public SkillUsageActorId(Long skillId, String action, String actorKey) {
        this.skillId = skillId;
        this.action = action;
        this.actorKey = actorKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillUsageActorId other)) {
            return false;
        }
        return Objects.equals(skillId, other.skillId)
                && Objects.equals(action, other.action)
                && Objects.equals(actorKey, other.actorKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillId, action, actorKey);
    }
}
