package com.stonewu.agenteam.service.data.mysql;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.data.mysql.ExternalMysqlSessions;
import com.stonewu.agenteam.mapper.data.mysql.ExternalMysqlSqlMapper;
import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * 连接仅允许加密、固定地址和专用只读账号，不使用数据库管理连接执行用户查询。
 */
@Service
public class MysqlReadOnlyConnections {
    private final DatabaseAddressPolicy addresses;
    private final DatabaseCredentialService credentials;
    private final String sslMode;

    public MysqlReadOnlyConnections(DatabaseAddressPolicy addresses, DatabaseCredentialService credentials,
                                    Environment environment,
                                    @Value("${data.mysql.ssl-mode:VERIFY_IDENTITY}") String sslMode) {
        this.addresses = addresses;
        this.credentials = credentials;
        this.sslMode = sslMode;
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("production");
        if (!Set.of("VERIFY_IDENTITY", "REQUIRED").contains(sslMode) || (production && !sslMode.equals(
            "VERIFY_IDENTITY"))) {
            throw new IllegalArgumentException(Messages.text("error.data.mysqlReadOnlyConnections.init.1"));
        }
    }

    public Session open(String enterprise, JsonNode config, long deadline) {
        return open(enterprise, config, deadline, null);
    }

    public Session open(String enterprise, JsonNode config, long deadline, ToolCallControl control) {
        var connection = config.path("connection");
        String host = connection.path("host").asText();
        int port = connection.path("port").asInt();
        String database = connection.path("database").asText();
        if (database.isBlank() || database.codePointCount(0, database.length()) > 64 || database.codePoints()
            .anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("config.connection.database", Messages.text("error.data.mysqlReadOnlyConnections.open.1"));
        }
        var resolved = addresses.resolve(host, port, deadline);
        var secret = credentials.resolve(enterprise, config.path("credentialId").asText(null));
        var properties = new Properties();
        properties.setProperty("user", secret.username());
        properties.setProperty("password", secret.password());
        properties.setProperty("socketFactory", PinnedMysqlSocketFactory.class.getName());
        properties.setProperty("sslMode", sslMode.equals("VERIFY_IDENTITY") ? "verify-full" : "trust");
        properties.setProperty("connectTimeout", Integer.toString(remaining(deadline)));
        properties.setProperty("socketTimeout", Integer.toString(remaining(deadline)));
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("allowLocalInfile", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("connectionTimeZone", "+00:00");
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        properties.setProperty("preserveInstants", "true");
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        Connection opened = null;
        var scope = PinnedMysqlSocketFactory.scope(host, port, resolved, deadline);
        try {
            if (control != null) {
                control.track(scope);
            }
            try {
                opened = DriverManager.getConnection("jdbc:mariadb://" + authority + ":" + port + "/", properties);
            } finally {
                scope.connected();
            }
            opened.setReadOnly(true);
            opened.setCatalog(database);
            var session = new Session(opened, scope, database, deadline, ExternalMysqlSessions.open(opened));
            requireReadOnly(session);
            return session;
        } catch (PersistenceException failed) {
            closeFailed(opened, scope);
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api) {
                    throw api;
                }
            }
            throw unavailable(failed);
        } catch (RuntimeException failed) {
            closeFailed(opened, scope);
            throw failed;
        } catch (SQLException failed) {
            closeFailed(opened, scope);
            throw unavailable(failed);
        } finally {
            scope.connected();
            properties.clear();
        }
    }

    private void requireReadOnly(Session session) {
        boolean[] selectable = {false};
        session.mapper().readGrants(new DataQueryBudget(session.deadline()), context -> {
            if (context.getResultCount() > 1000) {
                throw readOnlyRequired();
            }
            String grant = context.getResultObject(), upper = grant.toUpperCase(Locale.ROOT);
            int on = upper.indexOf(" ON "), to = upper.indexOf(" TO ");
            if (!upper.startsWith("GRANT ") || on < 6 || to <= on || upper.contains("WITH GRANT OPTION")) {
                throw readOnlyRequired();
            }
            String privileges = upper.substring(6, on), target = grant.substring(on + 4, to);
            if (privileges.equals("USAGE")) {
                return;
            }
            for (String privilege : privileges.split(",")) {
                if (!Set.of("SELECT", "SHOW VIEW").contains(privilege.trim())) {
                    throw readOnlyRequired();
                }
            }
            if (!target.startsWith("`" + session.database().replace("`", "``") + "`.")) {
                throw readOnlyRequired();
            }
            selectable[0] |= Arrays.stream(privileges.split(",")).anyMatch(value -> value.trim().equals("SELECT"));
        });
        if (!selectable[0]) {
            throw readOnlyRequired();
        }
    }

    public record Session(Connection connection, PinnedMysqlSocketFactory.Scope scope, String database, long deadline,
                          SqlSession mappings) implements AutoCloseable {
        public int remainingMillis() {
            return remaining(deadline);
        }

        public ExternalMysqlSqlMapper mapper() {
            return mappings.getMapper(ExternalMysqlSqlMapper.class);
        }

        @Override
        public void close() {
            scope.close();
            try {
                mappings.close();
            } catch (RuntimeException ignored) { /* 本次连接的实际网络已经关闭。 */ } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) { /* 确保连接不再被后续查询复用。 */ }
            }
        }
    }

    private void closeFailed(Connection connection, PinnedMysqlSocketFactory.Scope scope) {
        scope.close();
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) { /* 本次失败连接的实际网络已经关闭。 */ }
    }

    private static int remaining(long deadline) {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATA_QUERY_TIMEOUT", Messages.text("error.data.mysqlReadOnlyConnections.remaining.1"));
        }
        return (int) Math.min(10000, millis);
    }

    private ApiException readOnlyRequired() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_READ_ONLY_REQUIRED",
            Messages.text("error.data.mysqlReadOnlyConnections.readOnlyRequired.1"));
    }

    private ApiException unavailable() {
        return unavailable(null);
    }

    private ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "DATA_CONNECTION_FAILED",
            Messages.text("error.data.mysqlReadOnlyConnections.unavailable.1"), cause);
    }
}
