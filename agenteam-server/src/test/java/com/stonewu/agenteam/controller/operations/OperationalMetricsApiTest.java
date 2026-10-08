package com.stonewu.agenteam.controller.operations;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.usage.QuotaMaintenanceScheduling;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.mapper.usage.QuotaSqlMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.service.operations.OperationalMetrics;
import com.stonewu.agenteam.service.operations.OperationalQueryService;
import com.stonewu.agenteam.service.usage.QuotaReconciliationService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实网络验证管理入口隔离，真实执行与事务验证指标来源和失败处理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false", "operations.metrics.enabled=true", "operations.metrics.initial-delay-ms=3600000", "management.server.port=0", "management.server.address=127.0.0.1", "management.endpoints.web.exposure.include=health,prometheus", "management.prometheus.metrics.export.enabled=true"})
@Import(SharedEnterpriseTestEdition.class)
class OperationalMetricsApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private OperationalMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Environment environment;

    @Autowired
    private QuotaReconciliationService reconciliation;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private OperationalQueryService queries;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @AfterEach
    void resetReadFailure() {
        reset(queries);
    }

    @Test
    void managementPortOnlyExposesHealthAndMetricsWithoutBusinessDetails() throws Exception {
        int main = port("local.server.port"), management = port("local.management.port");
        assertNotEquals(main, management);
        metrics.collect();
        try (var http = HttpClient.newHttpClient()) {
            assertEquals(200, get(http, main, base() + "/home", true).statusCode());
            assertEquals(404, get(http, main, "/actuator/prometheus", false).statusCode());
            assertEquals(404, get(http, management, base() + "/home", true).statusCode());
            assertEquals(404, get(http, management, "/actuator/env", false).statusCode());
            assertEquals(404, get(http, management, "/actuator/heapdump", false).statusCode());
            var health = get(http, management, "/actuator/health", false);
            assertEquals(200, health.statusCode());
            assertEquals("UP", json.readTree(health.body()).path("status").asText());
            assertFalse(health.body().contains("components"));
            var response = get(http, management, "/actuator/prometheus", false);
            assertEquals(200, response.statusCode());
            String body = response.body();
            assertTrue(body.contains("agenteam_background_jobs"));
            assertTrue(body.contains("http_server_requests_seconds"));
            assertTrue(body.contains("jvm_memory_used_bytes"));
            assertTrue(body.contains("hikaricp_connections"));
            assertFalse(body.contains(enterprise));
            assertFalse(body.contains(admin));
            assertFalse(body.contains("内部指令"));
            assertFalse(body.contains("isolated-execution-secret"));
        }
    }

    @Test
    void queueAndUnpublishedEventsFollowActualStateAndFailedCollectionDoesNotRetainOldNumbers() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getStatus, "queued").set(BackgroundJobRow::getAvailableAt, (Timestamp.from(Instant.now().minusSeconds(180)))));
        metrics.collect();
        assertEquals(1, registry.get("agenteam.background.jobs").tags("kind", "run", "status", "ready").gauge().value());
        assertTrue(registry.get("agenteam.background.oldest").tag("kind", "run").gauge().value() >= 179);
        assertEquals(1, registry.get("agenteam.executions").tag("status", "queued").gauge().value());
        assertTrue(registry.get("agenteam.events.pending").gauge().value() > 0);
        double before = registry.get("agenteam.metrics.collection.failures").counter().count();
        double lastSuccess = registry.get("agenteam.metrics.last.success").gauge().value();
        doThrow(new IllegalStateException("模拟数据库暂时不可用")).when(queries).read();
        metrics.collect();
        assertTrue(Double.isNaN(registry.get("agenteam.executions").tag("status", "queued").gauge().value()));
        assertEquals(0, registry.get("agenteam.metrics.collection.available").gauge().value());
        assertEquals(lastSuccess, registry.get("agenteam.metrics.last.success").gauge().value());
        assertEquals(before + 1, registry.get("agenteam.metrics.collection.failures").counter().count());
        reset(queries);
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", Map.of(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        metrics.collect();
        assertEquals(0, registry.get("agenteam.executions").tag("status", "queued").gauge().value());
        assertEquals(0, registry.get("agenteam.background.jobs").tags("kind", "run", "status", "ready").gauge().value());
    }

    @Test
    void quotaMetricsOnlyCountCompletedChecksAndCommittedRepairs() throws Exception {
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        String bucket = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).operationalMetricsApiQuotaMetricsOnlyCountCompletedChecksAndCommittedRepairsObject(enterprise));
        databaseAccess.mapper(QuotaSqlMapper.class).update(new LambdaUpdateWrapper<QuotaBucketRow>().eq(QuotaBucketRow::getId, (bucket)).set(QuotaBucketRow::getUsedCount, 9));
        double before = registry.get("agenteam.quota.differences").counter().count();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            assertFalse(reconciliation.check(enterprise).isEmpty());
            transaction.setRollbackOnly();
        });
        assertEquals(9, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaSqlMapper.class).selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getUsedCount).eq(QuotaBucketRow::getId, (bucket))).stream().map(fixtureRecord -> (fixtureRecord.getUsedCount() == null ? null : Math.toIntExact(fixtureRecord.getUsedCount()))).toList()));
        assertEquals(before, registry.get("agenteam.quota.differences").counter().count());
        new QuotaMaintenanceScheduling(reconciliation, clock, publisher).poll();
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaSqlMapper.class).selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getUsedCount).eq(QuotaBucketRow::getId, (bucket))).stream().map(fixtureRecord -> (fixtureRecord.getUsedCount() == null ? null : Math.toIntExact(fixtureRecord.getUsedCount()))).toList()));
        assertEquals(before + 1, registry.get("agenteam.quota.differences").counter().count());
        assertTrue(registry.get("agenteam.quota.last.check").gauge().value() > 0);
    }

    private int port(String name) {
        return environment.getRequiredProperty(name, Integer.class);
    }

    private HttpResponse<String> get(HttpClient client, int port, String path, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authenticated) {
            request.header("Cookie", cookie.getName() + "=" + cookie.getValue());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
