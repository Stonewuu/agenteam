package com.stonewu.agenteam.service.data.mysql;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.test.source.DatabaseAccountFixtureMapper;
import com.stonewu.agenteam.mapper.test.source.SourceRowFixtureMapper;
import com.stonewu.agenteam.model.test.source.DatabaseTestAccount;
import com.stonewu.agenteam.model.test.source.SourceRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实际数据库验证地址固定、加密、只读账号与有上限的查询连接。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlReadOnlyConnectionsTest {

    private IsolatedDatabase database;

    private String name;

    private String reader;

    private String writer;

    private int port;

    private final String password = "p05-isolated-source-test-only!";

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void prepare() throws Exception {
        database = new IsolatedDatabase();
        port = IsolatedInfrastructure.mysql().getMappedPort(3306);
        reader = "p05r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        writer = "p05w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var access = database.databaseAccess();
        name = access.catalog();
        var rows = access.mapper(SourceRowFixtureMapper.class);
        rows.createTable();
        var row = new SourceRow();
        row.setId(1L);
        row.setTitle("只读来源内容");
        rows.insert(row);
        var accounts = access.mapper(DatabaseAccountFixtureMapper.class);
        accounts.create(account(reader));
        accounts.create(account(writer));
        accounts.allowDatabaseRead(account(reader));
        accounts.allowDatabaseWrite(account(writer));
    }

    @AfterAll
    void close() throws Exception {
        if (database == null) {
            return;
        }
        try {
            var accounts = database.databaseAccess().mapper(DatabaseAccountFixtureMapper.class);
            accounts.drop(account(reader));
            accounts.drop(account(writer));
        } finally {
            database.close();
        }
    }

    @Test
    void aNonResolvableHostnameUsesOnlyThePreviouslyCheckedAddressAndAReadOnlyAccount() throws Exception {
        var lookups = new AtomicInteger();
        var connections = connections(reader, "mysql://source.example.test:" + port, lookups);
        try (var session = connections.open("source-enterprise", config("source.example.test"), deadline())) {
            assertTrue(session.connection().isReadOnly());
            var rows = new MybatisTestDatabase(new SingleConnectionDataSource(session.connection(), true)).mapper(SourceRowFixtureMapper.class);
            assertEquals("只读来源内容", rows.selectById(1L).getTitle());
            assertFalse(rows.cipher().value().isBlank());
            sqlFailure(() -> rows.update(new LambdaUpdateWrapper<SourceRow>().eq(SourceRow::getId, 1L).set(SourceRow::getTitle, "禁止写入")));
        }
        assertEquals(1, lookups.get(), "驱动不能再次自行解析主机名");
        assertEquals("只读来源内容", database.databaseAccess().mapper(SourceRowFixtureMapper.class).selectById(1L).getTitle());
    }

    @Test
    void writableAccountsAndUnlistedTargetsAreRejected() {
        var mutable = connections(writer, "mysql://source.example.test:" + port, new AtomicInteger());
        assertEquals("DATABASE_READ_ONLY_REQUIRED", assertThrows(ApiException.class, () -> mutable.open("source-enterprise", config("source.example.test"), deadline())).code());
        var lookups = new AtomicInteger();
        var notAllowed = connections(reader, "mysql://allowed.example.test:" + port, lookups);
        assertEquals("DATABASE_ADDRESS_DENIED", assertThrows(ApiException.class, () -> notAllowed.open("source-enterprise", config("other.example.test"), deadline())).code());
        assertEquals(0, lookups.get());
    }

    @Test
    void productionConnectionsRejectAnUntrustedServerCertificate() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var connections = connections(reader, "mysql://source.example.test:" + port, new AtomicInteger(),
            environment, "VERIFY_IDENTITY");
        var failure = assertThrows(ApiException.class,
            () -> connections.open("source-enterprise", config("source.example.test"), deadline()));
        assertEquals("DATA_CONNECTION_FAILED", failure.code());
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    void aSlowQueryLosesItsSocketWhenTheOverallDeadlineExpires() throws Exception {
        var connections = connections(reader, "mysql://source.example.test:" + port, new AtomicInteger());
        long started = System.nanoTime();
        try (var session = connections.open("source-enterprise", config("source.example.test"), started + Duration.ofSeconds(2).toNanos())) {
            var rows = new MybatisTestDatabase(new SingleConnectionDataSource(session.connection(), true)).mapper(SourceRowFixtureMapper.class);
            sqlFailure(rows::waitForDelay);
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "不能等远端查询自行结束后才返回超时");
    }

    private MysqlReadOnlyConnections connections(String username, String allowed, AtomicInteger lookups) {
        return connections(username, allowed, lookups, new MockEnvironment(), "REQUIRED");
    }

    private MysqlReadOnlyConnections connections(String username, String allowed, AtomicInteger lookups,
                                                  MockEnvironment environment, String sslMode) {
        var credentials = mock(DatabaseCredentialService.class);
        when(credentials.resolve("source-enterprise", "source-credential")).thenReturn(new DatabaseCredentialService.Credentials(username, password));
        var addresses = new OutboundAddressPolicy("", host -> {
            lookups.incrementAndGet();
            return List.of(InetAddress.getByName("127.0.0.1"));
        });
        return new MysqlReadOnlyConnections(new DatabaseAddressPolicy(allowed, addresses), credentials, environment, sslMode);
    }

    private JsonNode config(String host) {
        return json.valueToTree(Map.of("sourceType", "mysql", "credentialId", "source-credential", "connection", Map.of("host", host, "port", port, "database", name)));
    }

    private long deadline() {
        return System.nanoTime() + Duration.ofSeconds(10).toNanos();
    }

    private DatabaseTestAccount account(String username) {
        return new DatabaseTestAccount(name, username, password);
    }

    private void sqlFailure(Runnable action) {
        var failure = assertThrows(RuntimeException.class, action::run);
        Throwable cause = failure;
        while (!(cause instanceof SQLException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertInstanceOf(SQLException.class, cause);
    }
}
