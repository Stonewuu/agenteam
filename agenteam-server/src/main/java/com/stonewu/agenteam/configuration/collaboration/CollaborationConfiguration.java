package com.stonewu.agenteam.configuration.collaboration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 岗位协作的服务端限制，任务创建后保存实际使用的值。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collaboration")
public class CollaborationConfiguration {
    private boolean enabled;
    private int maxWorks = 20;
    private int maxHandoffs = 8;
    private int maxRuns = 40;
    private int maxParallel = 2;
    private int deadlineHours = 24;
    private int maxContextCharacters = 24000;
    private int maxContextMessages = 30;
}
