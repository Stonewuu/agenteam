package com.stonewu.agenteam.configuration.chat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 聊天功能默认关闭，平台开关不会由外部消息或模型修改。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chat")
public class ChatConfiguration {
    private boolean enabled;
    private boolean workerEnabled = true;
    private int batchSize = 50;
    private int leaseSeconds = 30;
    private int maxMessageCharacters = 32000;
    private String publicBaseUrl;
    private Platform feishu = new Platform();
    private Platform wecomAibot = new Platform();

    @Getter
    @Setter
    public static class Platform {
        private boolean enabled;
    }

    public boolean enabled(String provider) {
        return enabled && switch (provider) {
            case "feishu" -> feishu.enabled;
            case "wecom_aibot" -> wecomAibot.enabled;
            default -> false;
        };
    }
}
