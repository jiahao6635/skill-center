package com.iflytek.skillhub.domain.usage;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Composite primary key of {@link SkillUsageDaily}: (skill_id, day, action).
 */
public class SkillUsageDailyId implements Serializable {

    private Long skillId;
    private LocalDate day;
    private String action;

    public SkillUsageDailyId() {}

    public SkillUsageDailyId(Long skillId, LocalDate day, String action) {
        this.skillId = skillId;
        this.day = day;
        this.action = action;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillUsageDailyId other)) {
            return false;
        }
        return Objects.equals(skillId, other.skillId)
                && Objects.equals(day, other.day)
                && Objects.equals(action, other.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillId, day, action);
    }
}
