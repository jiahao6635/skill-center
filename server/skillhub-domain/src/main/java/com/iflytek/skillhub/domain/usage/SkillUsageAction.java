package com.iflytek.skillhub.domain.usage;

/**
 * Usage actions recorded in the skill usage ledger. SEARCH is query-level
 * (skill id is always null); only DOWNLOAD and VIEW feed lifetime unique actors.
 */
public enum SkillUsageAction {
    DOWNLOAD,
    VIEW,
    SEARCH,
    UPLOAD,
    UPDATE,
    PUBLISH
}
