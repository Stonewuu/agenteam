package com.stonewu.agenteam.service.http;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import com.stonewu.agenteam.support.TestMybatisConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdempotentRequestServiceTest {

    @TempDir
    static Path directory;

    @Configuration
    @EnableTransactionManagement
    static class Transactions {
    }

    private static IsolatedDatabase database;

    private static AnnotationConfigApplicationContext first;

    private static AnnotationConfigApplicationContext second;

    private static String signingKey;

    private UserEntity user;

    private IdempotentRequestService requests;

    private IdempotentRequestService other;

    private MybatisTestDatabase databaseAccess;

    @BeforeAll
    static void start() throws Exception {
        database = new IsolatedDatabase();
        database.initialize();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        signingKey = Base64.getEncoder().encodeToString(key);
        first = services();
        second = services();
    }

    @AfterAll
    static void stop() throws Exception {
        if (second != null) {
            second.close();
        }
        if (first != null) {
            first.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private static AnnotationConfigApplicationContext services() {
        var context = new AnnotationConfigApplicationContext();
        var source = database.databaseAccess().getDataSource();
        context.registerBean(MybatisTestDatabase.class, () -> new MybatisTestDatabase(source));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ApplicationSecretKeys.class, () -> new ApplicationSecretKeys(signingKey, "", "1", directory.resolve("keys.properties").toString()));
        context.register(Transactions.class, AuthMapper.class, RequestFingerprint.class, IdempotentRequestService.class);
        context.register(TestMybatisConfiguration.class);
        context.refresh();
        return context;
    }

    @BeforeEach
    void createUser() {
        var users = first.getBean(AuthMapper.class);
        String id = UUID.randomUUID().toString();
        users.insertUser(id, "request-" + id, "stored-test-hash", "未修改", false, Instant.now());
        user = users.findById(id).orElseThrow();
        requests = first.getBean(IdempotentRequestService.class);
        other = second.getBean(IdempotentRequestService.class);
        databaseAccess = first.getBean(MybatisTestDatabase.class);
    }

    @Test
    void aRolledBackBusinessChangeLeavesNoCompletedRequestAndCanBeRetried() throws Exception {
        var request = request("rollback-request-key", "{\"name\":\"事务修改\"}");
        assertThrows(IllegalStateException.class, () -> requests.execute(request, user, null, Set.of(), () -> {
        }, () -> {
            databaseAccess.mapper(AppUserTableMapper.class).update(new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, (user.id())).set(AppUserRow::getDisplayName, "不应保存"));
            throw new IllegalStateException("模拟业务事务失败");
        }));
        assertEquals("未修改", displayName());
        assertEquals(0, storedCount());
        var completed = requests.execute(request, user, null, Set.of(), () -> {
        }, () -> save("事务修改"));
        assertFalse(completed.replayed());
        assertEquals("事务修改", displayName());
        assertEquals(1, storedCount());
    }

    @Test
    void independentInstancesExecuteOnlyOnceAndRecheckAuthorizationForSavedResponses() throws Exception {
        String key = "shared-request-key";
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrent(requests, request(key, "{\"name\":\"只修改一次\"}"), calls, ready, start));
            var b = executor.submit(() -> concurrent(other, request(key, "{\"name\":\"只修改一次\"}"), calls, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var resultA = a.get(10, TimeUnit.SECONDS);
            var resultB = b.get(10, TimeUnit.SECONDS);
            assertEquals(resultA.data(), resultB.data());
            assertEquals(Set.of(true, false), Set.of(resultA.replayed(), resultB.replayed()));
        }
        assertEquals(1, calls.get());
        assertEquals(1, storedCount());
        var rejected = assertThrows(ApiException.class, () -> requests.execute(request(key, "{\"name\":\"只修改一次\"}"), user, null, Set.of(), () -> {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "权限已撤销");
        }, () -> save("不应执行")));
        assertEquals("PERMISSION_DENIED", rejected.code());
        assertEquals("只修改一次", displayName());
    }

    @Test
    void aPendingRequestReturnsWithinTheBoundWithoutStartingASecondMutation() throws Exception {
        String key = "waiting-request-key";
        CountDownLatch inTransaction = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var pending = executor.submit(() -> requests.execute(request(key, "{}"), user, null, Set.of(), () -> {
            }, () -> {
                calls.incrementAndGet();
                inTransaction.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待测试结束超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return save("等待完成");
            }));
            assertTrue(inTransaction.await(5, TimeUnit.SECONDS));
            try {
                var retry = executor.submit(() -> assertThrows(ApiException.class, () -> other.execute(request(key, "{}"), user, null, Set.of(), () -> {
                }, () -> {
                    calls.incrementAndGet();
                    return save(other, "错误重复");
                })));
                assertEquals("REQUEST_IN_PROGRESS", retry.get(6, TimeUnit.SECONDS).code());
                assertEquals(1, calls.get());
            } finally {
                finish.countDown();
            }
            assertEquals(200, pending.get(5, TimeUnit.SECONDS).status());
        }
        assertTrue(other.execute(request(key, "{}"), user, null, Set.of(), () -> {
        }, () -> save("错误重复")).replayed());
        assertEquals("等待完成", displayName());
    }

    @Test
    void canonicalFieldOrderAndSetOrderDoNotCreateAnotherMutationButChangedTextDoes() throws Exception {
        String key = "canonical-request-key";
        requests.execute(request(key, "{\"roleIds\":[\"role-b\",\"role-a\"],\"name\":\"保留 空格\"}"), user, null, Set.of("/roleIds"), () -> {
        }, () -> save("原内容"));
        assertTrue(other.execute(request(key, "{\"name\":\"保留 空格\",\"roleIds\":[\"role-a\",\"role-b\"]}"), user, null, Set.of("/roleIds"), () -> {
        }, () -> save("错误重复")).replayed());
        var changed = assertThrows(ApiException.class, () -> requests.execute(request(key, "{\"name\":\"保留空格\",\"roleIds\":[\"role-a\",\"role-b\"]}"), user, null, Set.of("/roleIds"), () -> {
        }, () -> save("不应保存")));
        assertEquals("REQUEST_KEY_CONFLICT", changed.code());
        var duplicate = assertThrows(ApiException.class, () -> requests.execute(request(key, "{\"name\":\"保留 空格\",\"roleIds\":[\"role-a\",\"role-a\",\"role-b\"]}"), user, null, Set.of("/roleIds"), () -> {
        }, () -> save("不应保存")));
        assertEquals("VALIDATION_FAILED", duplicate.code());
        assertEquals(1, storedCount());
    }

    @Test
    void requestKeysRemainCaseSensitiveEvenWithCaseInsensitiveDatabaseColumns() throws Exception {
        requests.execute(request("Distinct-Request-Key", "{}"), user, null, Set.of(), () -> {
        }, () -> save("第一次"));
        assertFalse(other.execute(request("distinct-request-key", "{}"), user, null, Set.of(), () -> {
        }, () -> save(other, "第二次")).replayed());
        assertEquals(2, storedCount());
        assertEquals("第二次", displayName());
    }

    private ApiOperationResult concurrent(IdempotentRequestService service, MockHttpServletRequest request, AtomicInteger calls, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未启动");
        }
        return service.execute(request, user, null, Set.of(), () -> {
        }, () -> {
            calls.incrementAndGet();
            return save(service, "只修改一次");
        });
    }

    private MockHttpServletRequest request(String key, String body) throws Exception {
        var request = new MockHttpServletRequest("PATCH", "/api/v1/me/profile");
        request.addHeader("Idempotency-Key", key);
        request.addHeader("If-Match", "\"1\"");
        request.setAttribute(ApiRequestFilter.JSON_ATTRIBUTE, new ObjectMapper().readTree(body));
        return request;
    }

    private ApiOperationResult save(String name) {
        return save(requests, name);
    }

    private ApiOperationResult save(IdempotentRequestService service, String name) {
        MybatisTestDatabase transactionalDatabaseAccess = (service == requests ? first : second).getBean(MybatisTestDatabase.class);
        transactionalDatabaseAccess.mapper(AppUserTableMapper.class).update(new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, (user.id())).set(AppUserRow::getDisplayName, (name)));
        return ApiOperationResult.of(200, Map.of("displayName", name));
    }

    private int storedCount() {
        return Math.toIntExact(databaseAccess.mapper(ApiRequestMapper.class).selectCount(new LambdaQueryWrapper<ApiRequestRow>().eq(ApiRequestRow::getUserId, (user.id()))));
    }

    private String displayName() {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AppUserTableMapper.class).selectList(new LambdaQueryWrapper<AppUserRow>().select(AppUserRow::getDisplayName).eq(AppUserRow::getId, (user.id()))).stream().map(fixtureRecord -> fixtureRecord.getDisplayName()).toList());
    }
}
