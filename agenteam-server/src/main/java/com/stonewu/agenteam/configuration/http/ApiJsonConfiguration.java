package com.stonewu.agenteam.configuration.http;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/**
 * 请求字段和类型严格匹配，测试与部署不能因配置默认值不同而静默接受额外输入。
 */
@Configuration
public class ApiJsonConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer strictRequestJson() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
