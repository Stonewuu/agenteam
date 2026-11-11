package com.stonewu.agenteam.service.i18n;

import com.stonewu.agenteam.model.i18n.entity.UiLanguage;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import java.util.Locale;

/** 页面请求与独立配置校验使用同一份消息资源，保留约束参数的标准替换。 */
public final class ApplicationMessageInterpolator implements MessageInterpolator {
    private final MessageInterpolator standard = Validation.byDefaultProvider().configure().getDefaultMessageInterpolator();

    @Override
    public String interpolate(String template, Context context) {
        return interpolate(template, context, MessageLanguage.current().locale());
    }

    @Override
    public String interpolate(String template, Context context, Locale locale) {
        var language = UiLanguage.from(locale);
        String value = template;
        if (template.startsWith("{") && template.endsWith("}")) {
            String key = template.substring(1, template.length() - 1);
            if (Messages.contains(key)) {
                value = Messages.in(language, key);
            }
        }
        return standard.interpolate(Messages.localize(value, language), context, language.locale());
    }
}
