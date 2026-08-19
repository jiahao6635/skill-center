package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.label.LabelDefinition;
import com.iflytek.skillhub.domain.label.LabelDefinitionService;
import com.iflytek.skillhub.domain.label.LabelTranslation;
import com.iflytek.skillhub.dto.LabelTranslationResponse;
import com.iflytek.skillhub.dto.SkillLabelDto;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PublicLabelAppService {

    private final LabelDefinitionService labelDefinitionService;
    private final LabelLocalizationService labelLocalizationService;

    public PublicLabelAppService(LabelDefinitionService labelDefinitionService,
                                 LabelLocalizationService labelLocalizationService) {
        this.labelDefinitionService = labelDefinitionService;
        this.labelLocalizationService = labelLocalizationService;
    }

    public List<SkillLabelDto> listVisibleFilters() {
        List<LabelDefinition> definitions = labelDefinitionService.listVisibleFilters();
        Map<Long, List<LabelTranslation>> translationsByLabelId =
                labelDefinitionService.listTranslationsByLabelIds(definitions.stream().map(LabelDefinition::getId).toList());
        return definitions.stream()
                .map(labelDefinition -> toDto(labelDefinition, translationsByLabelId))
                .toList();
    }

    private SkillLabelDto toDto(LabelDefinition labelDefinition,
                                Map<Long, List<LabelTranslation>> translationsByLabelId) {
        List<LabelTranslation> translations = translationsByLabelId.getOrDefault(labelDefinition.getId(), List.of());
        return new SkillLabelDto(
                labelDefinition.getSlug(),
                labelDefinition.getType().name(),
                labelLocalizationService.resolveDisplayName(labelDefinition.getSlug(), translations),
                translations.stream()
                        .map(translation -> new LabelTranslationResponse(
                                translation.getLocale(),
                                translation.getDisplayName()))
                        .toList()
        );
    }
}
