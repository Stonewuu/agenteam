package com.stonewu.agenteam.configuration.security;

import com.stonewu.agenteam.service.auth.AccountInputValidation;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * 生产邮件链接和会话使用加密连接，部署凭据缺失时拒绝提供不能完成写入的服务。
 */
@Component
@Profile("production")
public class ProductionSettingsValidation {

    public ProductionSettingsValidation(Environment environment, ApplicationSecretKeys keys) {
        String publicBaseUrl = required(environment, "agenteam.web.public-base-url");
        URI uri = URI.create(publicBaseUrl);
        if (!"https".equalsIgnoreCase(
            uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath()
            .isEmpty())) {
            throw new IllegalArgumentException("生产邮件链接地址必须使用 HTTPS，且不带路径、查询参数或片段");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            throw new IllegalArgumentException("生产登录 Cookie 必须仅通过加密连接发送");
        }
        String proxies = required(environment, "server.tomcat.remoteip.internal-proxies");
        if (!Arrays.stream(proxies.split(",", -1)).allMatch(this::isSingleProxyAddress)) {
            throw new IllegalArgumentException(
                "可信代理必须逐一填写实际地址，IPv4 使用 /32，IPv6 使用 /128，不能配置整个网络或任意正则表达式");
        }
        required(environment, "spring.mail.host");
        AccountInputValidation.email(required(environment, "agenteam.mail.from"), "from");
        keys.requestKey();
        keys.activeVersion();
    }

    private boolean isSingleProxyAddress(String configured) {
        String value = configured.trim();
        boolean ipv4 = value.matches("[0-9]+(?:\\.[0-9]+){3}/32");
        boolean ipv6 = value.matches("[0-9a-fA-F:]+/128") && value.contains(":");
        if (!ipv4 && !ipv6) {
            return false;
        }
        try {
            // 格式检查只允许地址字面量，不执行主机名解析。
            InetAddress address = InetAddress.getByName(value.substring(0, value.indexOf('/')));
            return !address.isAnyLocalAddress() && !address.isMulticastAddress() && address.getAddress().length == (ipv4 ? 4 : 16);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private String required(Environment environment, String name) {
        String value = environment.getRequiredProperty(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少生产配置：" + name);
        }
        return value;
    }
}
