package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;

/** 平台消息链接只使用管理员配置的公开域名，不从消息或请求头推导网址。 */
@Component
public class ChatPublicLinks {
    private final ChatConfiguration configuration;
    private final String fallback;

    public ChatPublicLinks(ChatConfiguration configuration, @Value("${agenteam.web.public-base-url:http://localhost:3000}") String fallback) {
        this.configuration = configuration;
        this.fallback = fallback;
    }

    public String task(String enterprise, String task) {
        identifiers(enterprise, task);
        return base() + "/enterprises/" + enterprise + "/collaboration/tasks/" + task;
    }

    public String replyPreferences(String enterprise, String conversation) {
        identifiers(enterprise, conversation);
        return base() + "/settings?tab=robot-chat&enterprise=" + enterprise + "&conversation=" + conversation;
    }

    private String base() {
        String base = configuration.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = fallback;
        }
        var uri = URI.create(base);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("聊天公开访问地址配置不正确");
        }
        return base.replaceAll("/+$", "");
    }

    private void identifiers(String enterprise, String resource) {
        if (!enterprise.matches("[A-Za-z0-9_-]{1,100}") || !resource.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("聊天设置链接的记录编号不正确");
        }
    }
}
