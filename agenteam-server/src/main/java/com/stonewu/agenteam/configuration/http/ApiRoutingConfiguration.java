package com.stonewu.agenteam.configuration.http;

import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 按实际匹配的路由再次确认请求已校验，拒绝通过编码路径绕过过滤器。
 */
@Configuration
public class ApiRoutingConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {

            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (pattern != null && pattern.toString().startsWith("/api/v1/") && !Boolean.TRUE.equals(
                    request.getAttribute(ApiRequestFilter.VERIFIED_ATTRIBUTE))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "请求地址格式无法识别，请从页面重新操作。");
                }
                return true;
            }
        });
    }
}
