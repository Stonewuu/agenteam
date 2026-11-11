package com.stonewu.agenteam.configuration.i18n;

import com.stonewu.agenteam.service.i18n.MessageLanguage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 身份、防伪和参数校验之前确定语言，结束后恢复线程原上下文。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MessageLanguageFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var language = RequestLanguageResolver.resolve(request);
        var previous = LocaleContextHolder.getLocaleContext();
        try (var ignored = MessageLanguage.use(language)) {
            LocaleContextHolder.setLocale(language.locale());
            response.setHeader(HttpHeaders.CONTENT_LANGUAGE, language.tag());
            chain.doFilter(request, response);
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }
}
