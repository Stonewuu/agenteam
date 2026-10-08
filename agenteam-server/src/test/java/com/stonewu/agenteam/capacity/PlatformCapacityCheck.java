package com.stonewu.agenteam.capacity;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.capacity.CapacityDataGenerator.Enterprise;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单独执行 -Dtest=PlatformCapacityCheck；不在普通测试中反复生成百万级资料。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false"})
@Import({SharedEnterpriseTestEdition.class, CapacityInstrumentation.class})
class PlatformCapacityCheck extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final Path FILES = temporary();

    @Autowired
    private ResourceJson resourceJson;

    @Autowired
    private Environment environment;

    @Autowired
    private ExecutionStreamMeasurements streamMeasurements;

    @Autowired
    private ExecutionEventPublisher publisher;

    @Autowired
    private KnowledgeDatabaseTimings databaseTimes;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private record Session(String cookie, String csrf, Enterprise enterprise) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", FILES::toString);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> Integer.parseInt(System.getProperty("capacity.databaseConnections", "30")));
    }

    @AfterAll
    void closeCapacityEnvironment() throws Exception {
        try {
            client.close();
        } finally {
            try {
                ENVIRONMENT.close();
            } finally {
                removeGeneratedFiles();
            }
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void generatedDataMeasuresRealHttpReadsWritesAndRunAcceptance() throws Exception {
        var report = new LinkedHashMap<String, Object>();
        var times = new CapacityMeasurements();
        boolean executionPassed = true;
        report.put("startedAt", Instant.now().toString());
        report.put("status", "preparing");
        report.put("generatedFilesDirectory", FILES.toString());
        report.put("scope", "真实网络接口的普通读取、待办写入和执行提交；并发执行与事件分发另行验收");
        try {
            var generator = new CapacityDataGenerator(databaseAccess, resourceJson, users, permissions, provisioning, models, FILES);
            var enterprises = generator.create(enterprise, admin, agent, "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1");
            report.put("environment", Map.of("javaVersion", System.getProperty("java.runtime.version"), "applicationProcessors", Runtime.getRuntime().availableProcessors(), "applicationMaximumHeapBytes", Runtime.getRuntime().maxMemory(), "applicationDatabaseConnections", environment.getRequiredProperty("spring.datasource.hikari.maximum-pool-size"), "mysql", databaseAccess.mapper(TestDatabaseAdministrationMapper.class).environment()));
            report.put("generated", Map.of("enterprises", enterprises.size(), "membersPerEnterprise", 100, "resourcesPerEnterprise", 1000, "conversations", 1_000_000, "messages", 2_000_000, "knowledgeChunks", 1_000_000));
            report.put("status", "measuring");
            save(report, times);
            var sessions = new ArrayList<Session>();
            for (var item : enterprises) {
                sessions.add(login(item));
            }
            for (var session : sessions) {
                reads(session, null);
            }
            databaseTimes.clearSamples();
            for (int repeat = 0; repeat < 5; repeat++) {
                for (var session : sessions) {
                    reads(session, times);
                    writeTodo(session, times, repeat);
                    acceptRun(session, times);
                }
                report.put("completedRounds", repeat + 1);
                save(report, times);
                System.out.println("容量接口采样已完成：" + (repeat + 1) + "/5 轮");
            }
            databaseTimes.stopRecording();
            report.put("knowledgePlans", databaseTimes.plans(databaseAccess));
            report.put("status", times.passed() ? "passed" : "threshold_not_met");
            save(report, times);
            if (Boolean.getBoolean("capacity.compareKnowledgeQueries")) {
                report.put("knowledgeComparisons", databaseTimes.compareKnowledgeQueries(databaseAccess));
                save(report, times);
            }
            if (Boolean.getBoolean("capacity.checkExecution")) {
                report.put("scope", "规定数据规模的真实接口、同企业二十次活动执行和实际事件接收");
                allowModelCalls = true;
                try (var execution = new ExecutionCapacityProbe(databaseAccess, json, lifecycle, tasks, streamMeasurements, publisher, uri("/"))) {
                    modelResponse = execution::reply;
                    var result = execution.run(enterprises.getFirst());
                    report.put("executionCapacity", result);
                    save(report, times);
                    executionPassed = Boolean.TRUE.equals(result.get("passed"));
                }
            }
            if (Boolean.getBoolean("capacity.inspectAfterSampling")) {
                inspectGeneratedDatabase(enterprises.getFirst());
            }
            assertTrue(times.passed(), "实际接口第九十五百分位耗时超过正式阈值，请检查容量报告中的具体操作");
            assertTrue(executionPassed, "并发执行、排队、事件或资源占用未达到目标");
        } catch (Exception | AssertionError failure) {
            if (!"threshold_not_met".equals(report.get("status"))) {
                report.put("status", "failed");
            }
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            save(report, times);
        }
    }

    private void reads(Session session, CapacityMeasurements measurements) throws Exception {
        String base = base(session);
        String prefix = session.enterprise().prefix();
        var operations = Map.of("工作台", "/home", "对话列表", "/conversations?limit=30", "会话快照", "/conversations/" + prefix + "conversation_1_1", "员工列表", "/employees?tab=mine&limit=30", "资源列表", "/resources?kind=agent&limit=30", "统一搜索", "/search?query=容量");
        for (var item : operations.entrySet()) {
            var response = measurements == null ? send(session, "GET", base + item.getValue(), null) : measurements.measure(item.getKey(), 500, () -> send(session, "GET", base + item.getValue(), null));
            assertEquals(200, response.statusCode(), item.getKey());
            JsonNode result = json.readTree(response.body()).path("data");
            assertFalse(result.isMissingNode());
            if (item.getKey().equals("对话列表")) {
                assertEquals(30, result.path("items").size());
            }
        }
        var response = measurements == null ? send(session, "POST", base + "/knowledge/" + session.enterprise().knowledgeId() + "/search", Map.of("query", "负责人员", "limit", 8)) : measurements.measure("知识检索", 500, () -> send(session, "POST", base + "/knowledge/" + session.enterprise().knowledgeId() + "/search", Map.of("query", "负责人员", "limit", 8)));
        assertEquals(200, response.statusCode(), "知识检索");
        assertEquals(8, json.readTree(response.body()).path("data").size());
    }

    private void writeTodo(Session session, CapacityMeasurements measurements, int round) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "容量写入事项" + round);
        body.put("description", "生成的验收待办");
        body.put("ownerUserId", session.enterprise().sampleUser());
        body.put("teamId", null);
        body.put("dueDate", null);
        body.put("priority", "normal");
        body.put("sourceType", "manual");
        body.put("sourceConversationId", null);
        body.put("sourceMessageId", null);
        body.put("sourceRunId", null);
        var response = measurements.measure("待办写入", 800, () -> send(session, "POST", base(session) + "/todos", body));
        assertEquals(201, response.statusCode(), "待办写入");
    }

    private void acceptRun(Session session, CapacityMeasurements measurements) throws Exception {
        var response = measurements.measure("执行接受", 1000, () -> send(session, "POST", base(session) + "/conversations", Map.of("agentId", session.enterprise().agentId(), "input", input("请确认本次容量验收。"))));
        assertEquals(202, response.statusCode(), "执行接受");
        String run = json.readTree(response.body()).at("/data/runId").asText();
        assertFalse(run.isEmpty());
        assertEquals(202, send(session, "POST", base(session) + "/runs/" + run + "/cancel", Map.of()).statusCode(), "清理已接受的容量任务");
    }

    private Session login(Enterprise enterprise) throws Exception {
        var anonymous = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String cookie = sessionCookie(anonymous);
        String csrf = json.readTree(anonymous.body()).at("/data/token").asText();
        var response = send(new Session(cookie, csrf, enterprise), "POST", "/api/v1/auth/login", Map.of("identifier", enterprise.username(), "password", CapacityDataGenerator.PASSWORD));
        assertEquals(200, response.statusCode(), "容量成员登录");
        String active = sessionCookie(response);
        var token = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).header("Cookie", active).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Session(active, json.readTree(token.body()).at("/data/token").asText(), enterprise);
    }

    private String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")).findFirst().orElseThrow(() -> new IllegalStateException("容量验收没有取得实际登录会话")).split(";", 2)[0];
    }

    private HttpResponse<String> send(Session session, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60)).header("Cookie", session.cookie()).header("Accept", "application/json");
        if (method.equals("GET")) {
            request.GET();
        } else {
            request.header("Origin", "http://localhost:3000").header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private String base(Session session) {
        return "/api/v1/enterprises/" + session.enterprise().id();
    }

    private void inspectGeneratedDatabase(Enterprise enterprise) throws Exception {
        String database = databaseAccess.catalog();
        if (database == null || !database.matches("agenteam_test_[a-f0-9]{32}")) {
            throw new IllegalStateException("只允许检查本轮生成的隔离数据库");
        }
        URI connection = URI.create(environment.getRequiredProperty("spring.datasource.url").substring("jdbc:".length()));
        Path config = FILES.resolve("inspection-client.cnf"), stop = FILES.resolve("inspection.stop");
        Files.writeString(config, "[client]\nhost=" + connection.getHost() + "\nport=" + connection.getPort() + "\nuser=root\npassword=" + environment.getRequiredProperty("spring.datasource.password") + "\ndatabase=" + database + "\ndefault-character-set=utf8mb4\n");
        Files.writeString(Path.of("target/p09-capacity-inspection.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("processId", ProcessHandle.current().pid(), "connectionFile", config.toString(), "stopFile", stop.toString(), "database", database, "enterprise", enterprise.id(), "knowledge", enterprise.knowledgeId())));
        System.out.println("容量采样完成，本轮隔离数据库保留十分钟供查询诊断；写入 inspection.stop 后立即清理。");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
    }

    private void save(Map<String, Object> report, CapacityMeasurements measurements) throws IOException {
        report.put("measurements", measurements.report());
        report.put("knowledgeDatabaseMeasurements", databaseTimes.report());
        Files.writeString(Path.of("target/p09-capacity-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }

    private static Path temporary() {
        try {
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "capacity-files-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建容量验收文件目录", failure);
        }
    }

    private static void removeGeneratedFiles() throws IOException {
        Path target = Path.of("target").toRealPath(), generated = FILES.toRealPath();
        if (!generated.startsWith(target) || generated.equals(target) || !generated.getFileName().toString().startsWith("capacity-files-")) {
            throw new IllegalStateException("拒绝清理不属于本轮容量验收的文件目录");
        }
        try (var paths = Files.walk(generated)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(generated)) {
                    throw new IllegalStateException("生成的文件路径不在本轮验收目录内");
                }
                Files.delete(path);
            }
        }
    }
}
