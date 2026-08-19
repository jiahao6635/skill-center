package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.label.LabelDefinition;
import com.iflytek.skillhub.domain.label.LabelDefinitionService;
import com.iflytek.skillhub.domain.label.LabelTranslation;
import com.iflytek.skillhub.domain.label.LabelType;
import com.iflytek.skillhub.dto.SkillLabelDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicLabelAppServiceTest {

    private final LabelDefinitionService labelDefinitionService = mock(LabelDefinitionService.class);
    private final PublicLabelAppService service = new PublicLabelAppService(
            labelDefinitionService,
            new LabelLocalizationService()
    );

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void listVisibleFilters_includesTranslationsAndResolvesZhToZhCnDisplayName() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh"));
        LabelDefinition definition = new LabelDefinition("file-management", LabelType.RECOMMENDED, true, 0, "admin");
        ReflectionTestUtils.setField(definition, "id", 10L);
        when(labelDefinitionService.listVisibleFilters()).thenReturn(List.of(definition));
        when(labelDefinitionService.listTranslationsByLabelIds(List.of(10L))).thenReturn(Map.of(
                10L,
                List.of(
                        new LabelTranslation(10L, "en", "File Management"),
                        new LabelTranslation(10L, "zh-cn", "文件管理")
                )
        ));

        List<SkillLabelDto> labels = service.listVisibleFilters();

        assertThat(labels).hasSize(1);
        assertThat(labels.getFirst().slug()).isEqualTo("file-management");
        assertThat(labels.getFirst().displayName()).isEqualTo("文件管理");
        assertThat(labels.getFirst().translations())
                .extracting(com.iflytek.skillhub.dto.LabelTranslationResponse::displayName)
                .containsExactly("File Management", "文件管理");
    }
}
