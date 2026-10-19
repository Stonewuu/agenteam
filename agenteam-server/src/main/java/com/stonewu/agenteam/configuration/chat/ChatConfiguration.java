package com.stonewu.agenteam.configuration.chat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 已启用机器人默认可以工作；部署总开关仅用于紧急暂停聊天。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chat")
public class ChatConfiguration {
    private boolean enabled = true;
    private boolean workerEnabled = true;
    private int batchSize = 50;
    private int leaseSeconds = 30;
    private int maxMessageCharacters = 32000;
    private String publicBaseUrl;
    public boolean enabled(String provider) {
        return enabled && switch (provider) {
            case "feishu", "wecom_aibot" -> true;
            default -> false;
        };
    }
}
