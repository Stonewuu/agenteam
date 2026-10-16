package com.stonewu.agenteam.support;

import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 在本次测试容器中创建随机命名的空库，只允许清理该辅助类创建的库。
 */
public final class IsolatedDatabase implements AutoCloseable {
    private final IsolatedDatabaseName name;
    private final String url;
    private final String password;
    private MybatisTestDatabase administration;
    private MybatisTestDatabase access;
    private HikariDataSource pool;

    public IsolatedDatabase() throws SQLException {
        var mysql = IsolatedInfrastructure.mysql();
        name = new IsolatedDatabaseName("agenteam_test_" + UUID.randomUUID().toString().replace("-", ""));
        password = mysql.getPassword();
        String rootUrl = mysql.getJdbcUrl();
        url = rootUrl.replace("/" + mysql.getDatabaseName(), "/" + name.value());
        administration = MybatisTestDatabase.administration(new DriverManagerDataSource(rootUrl, "root", password));
        administration.mapper(TestDatabaseAdministrationMapper.class).createDatabase(name);
    }

    public Connection connection() throws SQLException {
        return dataSource().getConnection();
    }

    private synchronized HikariDataSource dataSource() {
        if (administration == null) {
            throw new IllegalStateException("隔离测试库已经关闭");
        }
        if (pool == null) {
            var config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername("root");
            config.setPassword(password);
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10000);
            config.setPoolName(name.value());
            pool = new HikariDataSource(config);
        }
        return pool;
    }

    public synchronized MybatisTestDatabase databaseAccess() {
        if (access == null) {
            access = new MybatisTestDatabase(dataSource());
        }
        return access;
    }

    public void initialize() {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/schema")
            .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load().migrate();
    }

    @Override
    public void close() throws SQLException {
        if (administration != null) {
            try {
                if (pool != null) {
                    pool.close();
                    pool = null;
                }
                administration.mapper(TestDatabaseAdministrationMapper.class).dropDatabase(name);
            } finally {
                administration = null;
                access = null;
            }
        }
    }
}
