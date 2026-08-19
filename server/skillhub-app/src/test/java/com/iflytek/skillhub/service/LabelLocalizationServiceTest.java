package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.label.LabelTranslation;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class LabelLocalizationServiceTest {

    private final LabelLocalizationService service = new LabelLocalizationService();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void resolveDisplayName_matchesZhRequestToZhCnTranslation() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh"));

        String displayName = service.resolveDisplayName(
                "code-generation",
                List.of(
                        new LabelTranslation(1L, "en", "Code Generation"),
                        new LabelTranslation(1L, "zh-cn", "代码生成")
                )
        );

        assertThat(displayName).isEqualTo("代码生成");
    }

    @Test
    void resolveDisplayName_matchesZhCnRequestToZhTranslation() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));

        String displayName = service.resolveDisplayName(
                "official",
                List.of(new LabelTranslation(1L, "zh", "官方"))
        );

        assertThat(displayName).isEqualTo("官方");
    }

    @Test
    void resolveDisplayName_prefersExactLanguageTagOverSameLanguageFallback() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));

        String displayName = service.resolveDisplayName(
                "official",
                List.of(
                        new LabelTranslation(1L, "zh", "官方"),
                        new LabelTranslation(1L, "zh-cn", "官方认证")
                )
        );

        assertThat(displayName).isEqualTo("官方认证");
    }

    @Test
    void resolveDisplayName_fallsBackToEnglishThenFirstTranslation() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        String englishFallback = service.resolveDisplayName(
                "official",
                List.of(
                        new LabelTranslation(1L, "zh-cn", "官方"),
                        new LabelTranslation(1L, "en", "Official")
                )
        );
        String firstTranslation = service.resolveDisplayName(
                "official",
                List.of(new LabelTranslation(1L, "zh-cn", "官方"))
        );

        assertThat(englishFallback).isEqualTo("Official");
        assertThat(firstTranslation).isEqualTo("官方");
    }

    @Test
    void resolveDisplayName_fallsBackToSlugWhenNoUsableTranslationExists() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(service.resolveDisplayName("official", List.of())).isEqualTo("official");
        assertThat(service.resolveDisplayName("official", List.of(new LabelTranslation(1L, "zh", "  "))))
                .isEqualTo("official");
    }
}
