package com.stonewu.agenteam.configuration.i18n;

import com.stonewu.agenteam.service.i18n.ApplicationMessageInterpolator;
import com.stonewu.agenteam.service.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import java.util.Locale;

@Configuration
public class MessageConfiguration implements WebMvcConfigurer {
    @Bean
    public LocaleResolver localeResolver() {
        Messages.verify();
        return new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return RequestLanguageResolver.resolve(request).locale();
            }
        };
    }

    @Bean
    @Override
    public LocalValidatorFactoryBean getValidator() {
        var validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new ApplicationMessageInterpolator());
        return validator;
    }
}
