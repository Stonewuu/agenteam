package com.stonewu.agenteam.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 整个测试进程共用的独立数据库；不会读取应用中的开发库地址和凭据。
 */
public final class IsolatedInfrastructure {
    private static GenericContainer<?> startedRedis;

    private static final class MysqlHolder {
        private static final MySQLContainer INSTANCE = startMysql();
    }

    private static final class RedisHolder {
        private static final GenericContainer<?> INSTANCE = startRedis();
    }

    private IsolatedInfrastructure() {
    }

    public static MySQLContainer mysql() {
        return MysqlHolder.INSTANCE;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    /** 只清理本测试进程自行创建的 Redis，其他进程使用不同容器和端口。 */
    public static void clearRedisAfterClass() throws IOException {
        var redis = startedRedis;
        if (redis == null || redis.getContainerId() == null) {
            return;
        }
        // 直接连接本进程的临时容器，避免每个测试类再启动一次 redis-cli。
        try (var connection = new Socket()) {
            connection.connect(new InetSocketAddress(redis.getHost(), redis.getMappedPort(6379)), 5000);
            connection.setSoTimeout(5000);
            connection.getOutputStream().write("*1\r\n$8\r\nFLUSHALL\r\n".getBytes(StandardCharsets.US_ASCII));
            var response = connection.getInputStream().readNBytes(5);
            if (!"+OK\r\n".equals(new String(response, StandardCharsets.US_ASCII))) {
                throw new IOException("本测试进程的临时 Redis 数据未能清理");
            }
        }
    }

    private static MySQLContainer startMysql() {
        MySQLContainer container = new MysqlCompatibleContainer()
            .withDatabaseName("agenteam")
            .withUsername("agenteam")
            .withPassword("isolated_test_only")
            .withCommand("--default-time-zone=+00:00");
        container.start();
        return container;
    }

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);
        container.start();
        startedRedis = container;
        return container;
    }
}
