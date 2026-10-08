package com.stonewu.agenteam.configuration.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 框架状态和平台内部配置使用 Jackson 的 JSON 读写器，公开响应由 Web 配置处理。
 */
@Configuration
public class JsonConfiguration {

    @Bean
    public ObjectMapper platformObjectMapper() {
        return new ObjectMapper();
    }
}
