package com.stonewu.agenteam.configuration.integration;

import com.stonewu.agenteam.service.i18n.Messages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/**
 * 授权回调地址使用部署配置，不采信浏览器请求域名或接入表单中的任意网址。
 */
@Component
public class IntegrationPublicUrls {
    private final URI origin;

    public IntegrationPublicUrls(@Value("${agenteam.web.public-base-url:http://localhost:3000}") String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !Set.of("", "/").contains(uri.getPath()) || !"https".equals(uri.getScheme())
            && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) {
            throw new IllegalStateException(Messages.text("error.integration.integrationPublicUrls.init.1"));
        }
        origin = uri.resolve("/");
    }

    public URI callback(String connection) {
        return origin.resolve("api/v1/auth/channel-callbacks/" + connection);
    }

    public URI login(String key) {
        return origin.resolve("login/channel/" + key);
    }

    public URI feishuManagementCallback(String connection) {
        return origin.resolve("api/v1/auth/feishu-management-callbacks/" + connection);
    }

    public URI notification(String enterprise, String id) {
        return origin.resolve("enterprises/" + enterprise + "/notifications/" + id);
    }
}
