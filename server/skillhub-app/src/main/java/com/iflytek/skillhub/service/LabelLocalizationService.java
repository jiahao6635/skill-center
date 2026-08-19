package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.label.LabelTranslation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class LabelLocalizationService {

    public String resolveDisplayName(String slug, List<LabelTranslation> translations) {
        Map<String, String> values = toDisplayNameByLocale(translations);
        if (values.isEmpty()) {
            return slug;
        }

        Locale locale = LocaleContextHolder.getLocale();
        String languageTag = normalizeLocale(locale == null ? null : locale.toLanguageTag());
        String language = normalizeLocale(locale == null ? null : locale.getLanguage());

        String exact = values.get(languageTag);
        if (exact != null) {
            return exact;
        }
        String languageOnly = values.get(language);
        if (languageOnly != null) {
            return languageOnly;
        }
        String sameLanguage = findByLanguagePrefix(values, language);
        if (sameLanguage != null) {
            return sameLanguage;
        }
        String english = values.get("en");
        if (english != null) {
            return english;
        }
        return values.values().iterator().next();
    }

    private Map<String, String> toDisplayNameByLocale(List<LabelTranslation> translations) {
        Map<String, String> values = new LinkedHashMap<>();
        if (translations == null) {
            return values;
        }
        for (LabelTranslation translation : translations) {
            String locale = normalizeLocale(translation.getLocale());
            String displayName = translation.getDisplayName();
            if (locale.isBlank() || displayName == null || displayName.isBlank()) {
                continue;
            }
            values.putIfAbsent(locale, displayName);
        }
        return values;
    }

    private String findByLanguagePrefix(Map<String, String> values, String language) {
        if (language.isBlank()) {
            return null;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (languageOf(entry.getKey()).equals(language)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String languageOf(String normalizedLocale) {
        int separator = normalizedLocale.indexOf('-');
        return separator < 0 ? normalizedLocale : normalizedLocale.substring(0, separator);
    }

    private String normalizeLocale(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
