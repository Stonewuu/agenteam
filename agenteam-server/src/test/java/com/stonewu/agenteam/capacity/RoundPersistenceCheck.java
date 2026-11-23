package com.stonewu.agenteam.capacity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.test.capacity.PersistenceDiagnosticsMapper;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手动执行的写入对照，账号、HTTP 接口、执行器、MySQL 与 Redis 均使用隔离环境。
 * 可通过进程环境提供模型凭据；默认使用固定八秒的本机流式模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "execution.worker.enabled=false", "execution.events.worker-enabled=true", "management.server.port=-1"
})
@Import({SharedEnterpriseTestEdition.class, PersistenceMeasurements.Configuration.class})
class RoundPersistenceCheck extends ExecutionApiTestSupport {
    private static final Logger LOG = LoggerFactory.getLogger(RoundPersistenceCheck.class);
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final String PASSWORD = "round-persistence-isolated-member-password";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final AtomicInteger modelActive = new AtomicInteger();
    private final AtomicInteger modelPeak = new AtomicInteger();
    @Autowired
    private PersistenceMeasurements measurements;
    @Autowired
    private RunStepMapper runSteps;
    @Autowired
    private ToolCallMapper toolCalls;
    @Autowired
    private ResourceGrantService grants;
    @Autowired
    private ResourceAuthorizationMapper resourceAccess;
    @Autowired
    private PersistenceDiagnosticsMapper diagnostics;
    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 30000);
        registry.add("execution.worker.maximum-concurrent", () -> -1);
        registry.add("execution.enterprise-active-runs", () -> -1);
        registry.add("execution.user-active-runs", () -> -1);
        registry.add("execution.events.live-retained-events", () -> 10000);
        registry.add("agenteam.storage.root", () -> TestOutputPaths.path("round-persistence/storage").toString());
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    private record Session(String cookie, String csrf) {
    }

    private record User(Session session, String conversation) {
    }

    private record Outcome(double submitMillis, double completeMillis, String status, int events, int resets,
                           long modelRounds, long toolCalls) {
    }

    @Test
    void measureCompleteApplicationRequests() throws Exception {
        int users = Integer.getInteger("agenteam.test.persistence-users", 100);
        assertTrue(users >= 1 && users <= 100);
        String supplied = System.getenv("AGENTEAM_TEST_MODEL_CREDENTIALS");
        boolean external = supplied != null && !supplied.isBlank();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("externalModel", external);
        metadata.put("users", users);
        metadata.put("workerConcurrent", -1);
        metadata.put("enterpriseActiveRuns", -1);
        metadata.put("userActiveRuns", -1);
        metadata.put("connectionPool", 10);
        metadata.put("connectionTimeoutMillis", 30000);
        metadata.put("processors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("processId", ProcessHandle.current().pid());
        metadata.put("mysql", diagnostics.settings());
        metadata.put("mysqlInMemoryStorage", Boolean.parseBoolean(System.getProperty("agenteam.test.mysql.tmpfs", "true")));
        var mysqlHost = IsolatedInfrastructure.mysql().getContainerInfo().getHostConfig();
        metadata.put("mysqlCpuNano", mysqlHost.getNanoCPUs());
        metadata.put("mysqlMemoryBytes", mysqlHost.getMemory());
        if (external) {
            var source = json.readTree(supplied);
            var capabilities = new ModelCapabilities(true, false, 8192, 1048576, List.of("text"));
            String selected = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin,
                "provider-validation", 1, "供应商模型验证", "openai", source.path("model").asText(),
                source.path("baseUrl").asText(), "MODEL", capabilities, true)), ignored -> source.path("apiKey").asText()).getFirst();
            configureAgent(config -> {
                config.put("modelProfileId", selected);
                config.remove("temperature");
                config.put("timeoutSeconds", 240);
                config.put("instructions", "请按用户要求简洁回答。需要工具时先简要说明要执行的操作，再调用工具。");
            });
            metadata.put("model", source.path("model").asText());
            metadata.put("provider", URI.create(source.path("baseUrl").asText()).getHost());
        } else {
            modelResponse = this::streamModel;
            metadata.put("model", "fixed-eight-second-stream");
            metadata.put("modelChunks", 40);
        }
        allowModelCalls = true;
        var members = prepareUsers(users);
        var poller = Executors.newSingleThreadScheduledExecutor();
        try (var worker = new RunWorker(lifecycle, tasks, null, -1)) {
            poller.scheduleWithFixedDelay(worker::poll, 0, 1000, TimeUnit.MILLISECONDS);
            try {
                phase("single", members.subList(0, 1), 0, "请用一句话说明数据库事务的作用。", metadata);
                if (external) {
                    phase("two-tool-rounds", members.subList(0, 1), 0,
                        "请先用一句话说明第一步，然后调用 list_files 查看当前目录；得到结果后，再用一句话说明第二步，"
                            + "并再次调用 list_files 查看当前目录。两次调用分两轮进行，不要并行。最后用一句话总结结果。", metadata);
                }
                if (users >= 10) {
                    phase("ten-burst", members.subList(0, 10), 0, "请只用一句话说明为什么要及时保存已完成的回复。", metadata);
                }
                if (users > 10) {
                    phase("hundred-burst", members, 0, "请用一句话说明数据库事务的作用。", metadata);
                    if (!external) {
                        phase("hundred-staggered", members, 600, "请用一句话说明数据库事务的作用。", metadata);
                    }
                }
            } finally {
                poller.shutdownNow();
                measurements.stop();
                writeReport(metadata);
            }
        }
    }

    private List<User> prepareUsers(int total) throws Exception {
        var owner = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        var resource = resourceAccess.find(enterprise, agent, false).orElseThrow();
        grants.replace(owner, agent, resource.revision(), List.of(new ResourceGrantSpec("enterprise", enterprise, "view"),
            new ResourceGrantSpec("enterprise", enterprise, "use")));
        var result = new ArrayList<User>();
        for (int index = 0; index < total; index++) {
            String name = "round_" + UUID.randomUUID().toString().substring(0, 12);
            var user = EnterpriseTestData.member(users, permissions, enterprise, name, PASSWORD, "写入验证成员",
                List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
            hires.establish(enterprise, user.id(), agent, Instant.now());
            var session = login(name, index);
            var accepted = request(session, "POST", base() + "/conversations",
                Map.of("agentId", agent, "input", input("准备独立对话")), 202);
            request(session, "POST", base() + "/runs/" + accepted.path("runId").asText() + "/cancel", Map.of(), 202);
            result.add(new User(session, accepted.path("conversationId").asText()));
        }
        return result;
    }

    private void phase(String name, List<User> selected, long spacing, String prompt, Map<String, Object> metadata) throws Exception {
        modelPeak.set(0);
        var before = databaseWrites();
        var window = measurements.start();
        long started = System.nanoTime();
        var outcomes = new ArrayList<CompletableFuture<Outcome>>();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var user : selected) {
                outcomes.add(CompletableFuture.supplyAsync(() -> {
                    long sent = System.nanoTime();
                    try {
                        var accepted = request(user.session(), "POST", base() + "/conversations/" + user.conversation() + "/messages",
                            input(prompt), 202);
                        double submitMillis = (System.nanoTime() - sent) / 1_000_000.0;
                        return readUntilFinished(user, accepted.path("runId").asText(), sent, submitMillis);
                    } catch (Exception failed) {
                        LOG.warn("Benchmark request failed; conversationId={}", user.conversation(), failed);
                        double elapsed = (System.nanoTime() - sent) / 1_000_000.0;
                        return new Outcome(elapsed, elapsed, failed instanceof HttpFailure http
                            ? "http_" + http.status : "request_failed", 0, 0, 0, 0);
                    }
                }, requests));
                if (spacing > 0) {
                    TimeUnit.MILLISECONDS.sleep(spacing);
                }
            }
            var all = new ArrayList<Outcome>();
            for (var outcome : outcomes) {
                all.add(outcome.get(300, TimeUnit.SECONDS));
            }
            long completed = System.nanoTime();
            TimeUnit.SECONDS.sleep(3);
            var after = databaseWrites();
            long settled = System.nanoTime();
            measurements.stop();
            var report = new LinkedHashMap<String, Object>();
            report.put("phase", name);
            report.put("requests", selected.size());
            report.put("completed", all.stream().filter(outcome -> outcome.status().equals("completed")).count());
            report.put("failed", all.stream().filter(outcome -> !outcome.status().equals("completed")).count());
            var statuses = new LinkedHashMap<String, Long>();
            all.forEach(outcome -> statuses.merge(outcome.status(), 1L, Long::sum));
            report.put("statuses", statuses);
            report.put("submitP95Millis", percentile(all.stream().map(Outcome::submitMillis).toList()));
            report.put("completeP95Millis", percentile(all.stream().map(Outcome::completeMillis).toList()));
            report.put("streamEvents", all.stream().mapToInt(Outcome::events).sum());
            report.put("snapshotReloads", all.stream().mapToInt(Outcome::resets).sum());
            if (!Boolean.TRUE.equals(metadata.get("externalModel"))) {
                report.put("modelPeak", modelPeak.get());
            }
            report.put("modelRounds", all.stream().mapToLong(Outcome::modelRounds).sum());
            report.put("toolCalls", all.stream().mapToLong(Outcome::toolCalls).sum());
            report.put("execution", window.report(completed));
            report.put("withThreeSecondSettle", window.report(settled));
            var database = new LinkedHashMap<String, Long>();
            for (String operation : List.of("inserts", "updates", "deletes")) {
                database.put(operation, after.get(operation) - before.get(operation));
            }
            report.put("databaseStatementDelta", database);
            report.put("requestWindowSeconds", (completed - started) / 1_000_000_000.0);
            results.add(report);
            writeReport(metadata);
            assertTrue(all.stream().allMatch(outcome -> outcome.status().equals("completed")), "存在未完成的模型执行，详见本轮报告");
            if (name.equals("two-tool-rounds")) {
                assertTrue(all.getFirst().toolCalls() >= 2 && all.getFirst().modelRounds() >= 3,
                    "模型必须完成至少两次工具调用和三轮模型生成");
            }
        } finally {
            measurements.stop();
        }
    }

    private Outcome readUntilFinished(User user, String run, long sent, double submitMillis) throws Exception {
        int count = 0, resets = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(280);
        while (System.nanoTime() < deadline) {
            var snapshot = request(user.session(), "GET", base() + "/conversations/" + user.conversation(), null, 200);
            for (var message : snapshot.path("messages")) {
                String status = message.path("status").asText();
                if (message.path("runId").asText().equals(run) && message.path("role").asText().equals("assistant")
                    && List.of("completed", "failed", "cancelled").contains(status)) {
                    return new Outcome(submitMillis, (System.nanoTime() - sent) / 1_000_000.0, status, count, resets,
                        runSteps.list(enterprise, run).stream().filter(step -> step.kind().equals("model")).count(),
                        toolCalls.forRun(enterprise, run).stream().filter(call -> call.status().equals("succeeded")).count());
                }
            }
            String query = "?after=" + snapshot.path("lastSequence").asText("0");
            if (snapshot.path("protocolVersion").asInt() == 2) {
                if (snapshot.path("streamCursor").isNull()) {
                    TimeUnit.MILLISECONDS.sleep(200);
                    continue;
                }
                query = "?after=" + snapshot.at("/streamCursor/sequence").asText() + "&generation="
                    + snapshot.at("/streamCursor/generation").asText();
            }
            var response = client.send(HttpRequest.newBuilder(uri(base() + "/conversations/" + user.conversation() + "/events" + query))
                .header("Cookie", user.session().cookie()).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(280)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String name = "", line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.isEmpty()) {
                        if (name.equals("stream.reset")) {
                            resets++;
                            break;
                        }
                        if (!name.isEmpty() && !name.equals("stream.ready")) {
                            count++;
                        }
                        if (List.of("run.completed", "run.failed", "run.cancelled").contains(name)) {
                            break;
                        }
                        name = "";
                    }
                }
            }
        }
        throw new IllegalStateException("Execution did not finish before the benchmark deadline");
    }

    private Session login(String username, int clientIndex) throws Exception {
        // 准备不同客户端的已登录账号，压力计时只覆盖后续网络消息请求。
        RequestPostProcessor address = request -> {
            request.setRemoteAddr("192.0.2." + (clientIndex + 1));
            return request;
        };
        var anonymous = mvc.perform(get("/api/v1/auth/csrf").with(address)).andExpect(status().isOk()).andReturn();
        var anonymousCookie = anonymous.getResponse().getCookie(sessionCookieName);
        var loggedIn = mvc.perform(post("/api/v1/auth/login").with(address).cookie(anonymousCookie)
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", data(anonymous).path("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier", username, "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        var authenticatedCookie = loggedIn.getResponse().getCookie(sessionCookieName);
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").with(address).cookie(authenticatedCookie))
            .andExpect(status().isOk()).andReturn();
        return new Session(authenticatedCookie.getName() + "=" + authenticatedCookie.getValue(),
            data(csrfResponse).path("token").asText());
    }

    private HttpRequest.Builder builder(Session session, String method, String path, Object body) throws IOException {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(90)).header("Cookie", session.cookie())
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", session.csrf())
            .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private JsonNode request(Session session, String method, String path, Object body, int expected) throws Exception {
        var response = client.send(builder(session, method, path, body).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new HttpFailure(response.statusCode(), path);
        }
        return json.readTree(response.body()).path("data");
    }

    private static final class HttpFailure extends IOException {
        private final int status;

        private HttpFailure(int status, String path) {
            super("HTTP " + status + " from " + path);
            this.status = status;
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private double percentile(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private Map<String, Long> databaseWrites() {
        var values = new LinkedHashMap<String, Long>();
        for (var row : diagnostics.writes()) {
            values.put(row.get("Variable_name").toString().substring(4).toLowerCase() + "s",
                Long.parseLong(row.get("Value").toString()));
        }
        assertEquals(3, values.size(), "数据库必须返回三类写语句计数");
        return values;
    }

    private void writeReport(Map<String, Object> metadata) throws IOException {
        var report = TestOutputPaths.path("round-persistence/report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("environment", metadata, "phases", results)));
    }

    private void streamModel(HttpExchange exchange) throws IOException {
        int active = modelActive.incrementAndGet();
        modelPeak.accumulateAndGet(active, Math::max);
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            for (int index = 0; index < 40; index++) {
                var choice = new LinkedHashMap<String, Object>();
                choice.put("index", 0);
                choice.put("delta", Map.of("content", "片段"));
                choice.put("finish_reason", index == 39 ? "stop" : null);
                var value = Map.of("id", "fixed-stream", "choices", List.of(choice));
                exchange.getResponseBody().write(("data: " + json.writeValueAsString(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Model stream interrupted", interrupted);
                }
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } finally {
            modelActive.decrementAndGet();
            exchange.close();
        }
    }
}
