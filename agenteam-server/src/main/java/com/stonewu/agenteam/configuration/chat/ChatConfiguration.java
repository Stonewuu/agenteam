package com.stonewu.agenteam.configuration.chat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 聊天后台处理参数；机器人启停由管理页面保存的状态决定。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chat")
public class ChatConfiguration {
    private boolean workerEnabled = true;
    private int batchSize = 50;
    private int leaseSeconds = 30;
    private int maxMessageCharacters = 32000;
    private String publicBaseUrl;
}
