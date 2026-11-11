package com.stonewu.agenteam.service.i18n;

import com.stonewu.agenteam.model.i18n.entity.UiLanguage;
import java.util.Locale;

/** 只在当前操作内选择语言，不修改进程默认语言或其他请求。 */
public final class MessageLanguage {
    private static final ThreadLocal<UiLanguage> CURRENT = new ThreadLocal<>();

    private MessageLanguage() {
    }

    public static UiLanguage current() {
        UiLanguage language = CURRENT.get();
        return language == null ? UiLanguage.CHINESE : language;
    }

    public static UiLanguage negotiate(String header) {
        if (header == null || header.isBlank()) {
            return UiLanguage.CHINESE;
        }
        try {
            for (var range : Locale.LanguageRange.parse(header)) {
                if (range.getWeight() <= 0) {
                    continue;
                }
                String primary = range.getRange().split("-", 2)[0];
                if (primary.equals("en")) {
                    return UiLanguage.ENGLISH;
                }
                if (primary.equals("zh")) {
                    return UiLanguage.CHINESE;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 无法识别的请求语言不影响业务请求。
        }
        return UiLanguage.CHINESE;
    }

    public static Scope use(UiLanguage language) {
        return new Scope(language);
    }

    public static final class Scope implements AutoCloseable {
        private final UiLanguage previous;

        private Scope(UiLanguage language) {
            previous = CURRENT.get();
            CURRENT.set(language);
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
