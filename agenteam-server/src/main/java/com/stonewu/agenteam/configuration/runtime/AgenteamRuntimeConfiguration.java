package com.stonewu.agenteam.configuration.runtime;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** 两版共用业务组件扫描，不扫描根包的启动入口或提前加载默认发行配置。 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "com.stonewu.agenteam", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class)
})
public class AgenteamRuntimeConfiguration {
}
