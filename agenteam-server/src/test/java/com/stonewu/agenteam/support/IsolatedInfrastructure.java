package com.stonewu.agenteam.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 整个测试进程共用的独立数据库；不会读取应用中的开发库地址和凭据。
 */
public final class IsolatedInfrastructure {

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
        return container;
    }
}
