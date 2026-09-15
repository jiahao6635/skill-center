package com.iflytek.skillhub.domain.skill;

public enum SkillShareStatus {
    SCANNING, PENDING_REVIEW, COMPLETED, REJECTED, WITHDRAWN, FAILED;

    public boolean isActive() { return this == SCANNING || this == PENDING_REVIEW; }
}
