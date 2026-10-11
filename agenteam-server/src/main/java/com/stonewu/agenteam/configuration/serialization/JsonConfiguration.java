package com.stonewu.agenteam.configuration.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 框架状态和平台内部配置使用 Jackson 的 JSON 读写器，公开响应由 Web 配置处理。
 */
@Configuration
public class JsonConfiguration {

    @Bean
    public ObjectMapper platformObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
