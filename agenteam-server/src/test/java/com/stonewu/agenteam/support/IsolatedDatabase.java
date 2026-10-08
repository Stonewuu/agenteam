package com.stonewu.agenteam.support;

import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 在本次测试容器中创建随机命名的空库，只允许清理该辅助类创建的库。
 */
public final class IsolatedDatabase implements AutoCloseable {
    private final IsolatedDatabaseName name;
    private final String url;
    private final String password;
    private final MybatisTestDatabase administration;
    private MybatisTestDatabase access;

    public IsolatedDatabase() throws SQLException {
        var mysql = IsolatedInfrastructure.mysql();
        name = new IsolatedDatabaseName("agenteam_test_" + UUID.randomUUID().toString().replace("-", ""));
        password = mysql.getPassword();
        String rootUrl = mysql.getJdbcUrl();
        url = rootUrl.replace("/" + mysql.getDatabaseName(), "/" + name.value());
        administration = new MybatisTestDatabase(new DriverManagerDataSource(rootUrl, "root", password));
        administration.mapper(TestDatabaseAdministrationMapper.class).createDatabase(name);
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, "root", password);
    }

    public synchronized MybatisTestDatabase databaseAccess() {
        if (access == null) {
            access = new MybatisTestDatabase(new DriverManagerDataSource(url, "root", password));
        }
        return access;
    }

    public void initialize() {
        Flyway.configure().dataSource(url, "root", password).locations("classpath:db/schema")
            .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load().migrate();
    }

    @Override
    public void close() throws SQLException {
        administration.mapper(TestDatabaseAdministrationMapper.class).dropDatabase(name);
    }
}
