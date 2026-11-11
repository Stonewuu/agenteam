package com.stonewu.agenteam.configuration.i18n;

import com.stonewu.agenteam.model.i18n.entity.UiLanguage;
import com.stonewu.agenteam.service.i18n.MessageLanguage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/** 已选界面语言优先于浏览器默认语言，未知值不影响请求处理。 */
public final class RequestLanguageResolver {
    private static final String LOCALE_COOKIE = "agenteam-locale";

    private RequestLanguageResolver() {
    }

    public static UiLanguage resolve(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (LOCALE_COOKIE.equals(cookie.getName())) {
                    for (UiLanguage language : UiLanguage.values()) {
                        if (language.tag().equals(cookie.getValue())) {
                            return language;
                        }
                    }
                }
            }
        }
        return MessageLanguage.negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }
}
