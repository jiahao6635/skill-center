package com.iflytek.skillhub.dto;

import java.util.List;

public record SkillLabelDto(
        String slug,
        String type,
        String displayName,
        List<LabelTranslationResponse> translations
) {
    public SkillLabelDto {
        translations = translations == null ? List.of() : List.copyOf(translations);
    }

    public SkillLabelDto(String slug, String type, String displayName) {
        this(slug, type, displayName, List.of());
    }
}
