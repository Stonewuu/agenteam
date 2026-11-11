package com.stonewu.agenteam.service.data.mysql;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 所有数据库目标都必须由部署配置明确允许，普通资源编辑不能自行放开内网连接。
 */
@Component
public class DatabaseAddressPolicy {
    private final Set<String> allowed;
    private final OutboundAddressPolicy addresses;

    public DatabaseAddressPolicy(@Value("${data.mysql.allowed-origins:}") String origins,
                                 OutboundAddressPolicy addresses) {
        this.addresses = addresses;
        allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(value -> {
            URI uri = URI.create(value);
            if (!"mysql".equals(
                uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException(Messages.text("error.data.databaseAddressPolicy.init.1"));
            }
            return origin(uri.getHost(), uri.getPort());
        }).collect(Collectors.toUnmodifiableSet());
    }

    public List<InetAddress> resolve(String host, int port, long deadline) {
        if (host == null || host.isBlank() || host.length() > 253 || port < 1 || port > 65535 || host.chars()
            .anyMatch(value -> value <= 32 || value == 127)
            || !allowed.contains(origin(host, port))) {
            throw denied();
        }
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) {
            throw denied();
        }
        return addresses.resolveHost(host, Duration.ofMillis(Math.min(10000, remaining)), true);
    }

    private String origin(String host, int port) {
        return host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "") + ":" + port;
    }

    private ApiException denied() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_ADDRESS_DENIED",
            Messages.text("error.data.databaseAddressPolicy.denied.1"));
    }
}
