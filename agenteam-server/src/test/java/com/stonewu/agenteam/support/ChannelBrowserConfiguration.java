package com.stonewu.agenteam.support;

import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

/** 必须由隔离验收启动器显式加载，正式应用不包含此配置。 */
@TestConfiguration
public class ChannelBrowserConfiguration {
    @Bean(destroyMethod = "close")
    ChannelBrowserPlatform channelBrowserPlatform(@Value("${agenteam.web.public-base-url}") String frontend) throws IOException {
        return new ChannelBrowserPlatform(frontend);
    }

    @Bean
    @Primary
    IntegrationEndpoints channelBrowserEndpoints(ChannelBrowserPlatform platform) {
        return new IntegrationEndpoints(platform.origin(), platform.origin(), platform.origin());
    }
}
