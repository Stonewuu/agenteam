package com.stonewu.agenteam.model.i18n.entity;

import java.util.Locale;

/** 用户界面支持的语言，未选择时沿用中文。 */
public enum UiLanguage {
    CHINESE("zh-CN", Locale.SIMPLIFIED_CHINESE),
    ENGLISH("en", Locale.ENGLISH);

    private final String tag;
    private final Locale locale;

    UiLanguage(String tag, Locale locale) {
        this.tag = tag;
        this.locale = locale;
    }

    public String tag() {
        return tag;
    }

    public Locale locale() {
        return locale;
    }

    public static UiLanguage from(Locale locale) {
        return locale != null && "en".equals(locale.getLanguage()) ? ENGLISH : CHINESE;
    }
}
