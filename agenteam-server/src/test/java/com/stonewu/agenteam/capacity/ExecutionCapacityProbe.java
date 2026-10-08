package com.stonewu.agenteam.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.service.execution.ExecutionTaskFactory;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import com.sun.net.httpserver.HttpExchange;
import org.springframework.dao.support.DataAccessUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个正式工作进程共同承载同企业二十次执行，真实网络读取事件并观察五轮资源占用。
 */
final class ExecutionCapacityProbe implements AutoCloseable {

    private record Session(String cookie, String csrf) {
    }

    private record Submission(Session session, String marker, String run, String conversation) {
    }

    private final MybatisTestDatabase databaseAccess;

    private final ObjectMapper json;

    private final RunLifecycleService lifecycle;

    private final ExecutionTaskFactory tasks;

    private final ExecutionEventPublisher publisher;

    private final URI origin;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final ExecutionStreamMeasurements measurements;

    private final Map<String, Object> progress = new LinkedHashMap<>();

    private final Map<String, Long> modelStarts = new ConcurrentHashMap<>();

    private final List<InputStream> openStreams = new ArrayList<>();

    private volatile CountDownLatch modelReady;

    private volatile CountDownLatch readersReady;

    private volatile CountDownLatch emit;

    private final AtomicInteger maximumWorkerThreads = new AtomicInteger();

    ExecutionCapacityProbe(MybatisTestDatabase databaseAccess, ObjectMapper json, RunLifecycleService lifecycle, ExecutionTaskFactory tasks, ExecutionStreamMeasurements measurements, ExecutionEventPublisher publisher, URI origin) {
        this.databaseAccess = databaseAccess;
        this.json = json;
        this.lifecycle = lifecycle;
        this.tasks = tasks;
        this.measurements = measurements;
        this.publisher = publisher;
        this.origin = origin;
    }

    public Map<String, Object> run(CapacityDataGenerator.Enterprise enterprise) throws Exception {
        var result = progress;
        var rounds = new ArrayList<Map<String, Object>>();
        result.put("rounds", rounds);
        measurements.start();
        var sessions = new ArrayList<Session>();
        for (int member = 1; member <= 10; member++) {
            sessions.add(login(enterprise.prefix() + "user_" + member));
        }
        String base = "/api/v1/enterprises/" + enterprise.id();
        var polling = Executors.newScheduledThreadPool(3);
        try (var first = new RunWorker(lifecycle, tasks);
             var second = new RunWorker(lifecycle, tasks);
             var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                polling.scheduleWithFixedDelay(first::poll, 0, 1000, TimeUnit.MILLISECONDS);
                polling.scheduleWithFixedDelay(second::poll, 0, 1000, TimeUnit.MILLISECONDS);
                polling.scheduleWithFixedDelay(publisher::publishPending, 0, 100, TimeUnit.MILLISECONDS);
                for (int round = 0; round < 5; round++) {
                    result.put("currentRound", round + 1);
                    modelStarts.clear();
                    modelReady = new CountDownLatch(20);
                    readersReady = new CountDownLatch(20);
                    emit = new CountDownLatch(1);
                    var submissions = new ArrayList<Future<Submission>>();
                    for (int n = 0; n < 20; n++) {
                        var session = sessions.get(n / 2);
                        String marker = "$CAPACITY_" + round + "_" + n + "$";
                        modelStarts.put(marker, 0L);
                        submissions.add(requests.submit(() -> {
                            var input = Map.of("text", "请分段返回容量验收结果 " + marker, "attachmentIds", List.of(), "skillVersionIds", List.of(), "knowledgeReferences", List.of(), "links", List.of());
                            var accepted = send(session, "POST", base + "/conversations", Map.of("agentId", enterprise.agentId(), "input", input), 202);
                            return new Submission(session, marker, accepted.path("runId").asText(), accepted.path("conversationId").asText());
                        }));
                    }
                    var accepted = new ArrayList<Submission>();
                    for (var pending : submissions) {
                        accepted.add(pending.get(30, TimeUnit.SECONDS));
                    }
                    result.put("latestRunIds", accepted.stream().map(Submission::run).toList());
                    assertTrue(modelReady.await(30, TimeUnit.SECONDS), "二十次执行没有全部到达模型服务");
                    assertEquals(20, Math.toIntExact(databaseAccess.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, (enterprise.id())).eq(AgentRunRow::getStatus, "running"))));
                    int workers = (int) Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().equals("execution-worker")).count();
                    maximumWorkerThreads.accumulateAndGet(workers, Math::max);
                    assertTrue(workers <= 20, "两个工作进程的执行线程超过二十条");
                    var readers = new ArrayList<Future<?>>();
                    for (var submission : accepted) {
                        measurements.started(submission.run(), modelStarts.get(submission.marker()));
                        readers.add(requests.submit(() -> {
                            readEvents(base, submission);
                            return null;
                        }));
                    }
                    assertTrue(readersReady.await(15, TimeUnit.SECONDS), "事件连接没有完成当前快照之后的读取准备");
                    emit.countDown();
                    for (var reader : readers) {
                        reader.get(30, TimeUnit.SECONDS);
                    }
                    for (var submission : accepted) {
                        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (submission.run()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
                    }
                    measurements.clearCompletedEvents();
                    System.gc();
                    rounds.add(Map.of("round", round + 1, "activeRuns", 20, "workerThreads", workers, "platformThreadsAfter", ManagementFactory.getThreadMXBean().getThreadCount(), "heapBytesAfterCollection", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
                    System.out.println("二十次并发执行及实际事件读取已完成：" + (round + 1) + "/5 轮");
                }
            } finally {
                if (emit != null) {
                    emit.countDown();
                }
                polling.shutdownNow();
                synchronized (openStreams) {
                    for (var stream : openStreams) {
                        stream.close();
                    }
                    openStreams.clear();
                }
            }
        }
        var warmed = rounds.subList(1, rounds.size());
        long smallestHeap = warmed.stream().mapToLong(row -> ((Number) row.get("heapBytesAfterCollection")).longValue()).min().orElseThrow();
        long largestHeap = warmed.stream().mapToLong(row -> ((Number) row.get("heapBytesAfterCollection")).longValue()).max().orElseThrow();
        int firstThreads = ((Number) warmed.getFirst().get("platformThreadsAfter")).intValue(), lastThreads = ((Number) warmed.getLast().get("platformThreadsAfter")).intValue();
        result.put("timings", measurements.report());
        result.put("maximumWorkerThreads", maximumWorkerThreads.get());
        result.put("heapGrowthBytesAfterWarmup", largestHeap - smallestHeap);
        result.put("platformThreadGrowthAfterWarmup", lastThreads - firstThreads);
        result.put("resourceObservation", "第一轮用于初始化；之后四轮回收后的堆占用差不超过一百二十八 MiB，平台线程净增长不超过三十二条；执行线程最多二十条。");
        result.put("passed", measurements.passed() && largestHeap - smallestHeap < 128L * 1024 * 1024 && lastThreads - firstThreads <= 32);
        return result;
    }

    public void reply(HttpExchange exchange) throws IOException {
        var request = json.readTree(exchange.getRequestBody());
        String serialized = request.toString();
        String marker = modelStarts.keySet().stream().filter(serialized::contains).findFirst().orElseThrow(() -> new IOException("模型没有收到本轮并发任务编号"));
        modelStarts.put(marker, System.nanoTime());
        modelReady.countDown();
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try {
            frame(exchange, Map.of("role", "assistant", "content", "已经开始处理。"), null);
            if (!emit.await(30, TimeUnit.SECONDS)) {
                throw new IOException("事件读取准备超时");
            }
            for (int n = 0; n < 20; n++) {
                frame(exchange, Map.of("content", "容量实时片段" + n + "；"), null);
                Thread.sleep(30);
            }
            frame(exchange, Map.of("content", "本次结果已完成。"), "stop");
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private void frame(HttpExchange exchange, Object delta, String finish) throws IOException {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        var chunk = Map.of("id", "capacity-stream", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(choice));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private void readEvents(String base, Submission submission) throws Exception {
        var response = client.send(HttpRequest.newBuilder(origin.resolve(base + "/conversations/" + submission.conversation() + "/events")).header("Cookie", submission.session().cookie()).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        var body = response.body();
        synchronized (openStreams) {
            openStreams.add(body);
        }
        long readyAt = Long.MAX_VALUE;
        String name = "";
        var data = new StringBuilder();
        try (body;
             var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading());
                } else if (line.isEmpty()) {
                    if (name.equals("stream.ready")) {
                        if (readyAt == Long.MAX_VALUE) {
                            readyAt = System.nanoTime();
                            readersReady.countDown();
                        }
                    } else if (!data.isEmpty()) {
                        var event = json.readTree(data.toString());
                        measurements.received(event, readyAt);
                        if (name.equals("run.completed")) {
                            return;
                        }
                        if (name.equals("run.failed") || name.equals("stream.reset")) {
                            throw new IOException("实际事件流没有完成：" + name);
                        }
                    }
                    name = "";
                    data.setLength(0);
                }
            }
            throw new IOException("事件连接在执行完成前结束");
        } finally {
            synchronized (openStreams) {
                openStreams.remove(body);
            }
        }
    }

    private Session login(String username) throws Exception {
        var anonymous = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var initial = new Session(cookie(anonymous), json.readTree(anonymous.body()).at("/data/token").asText());
        var request = HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login")).header("Cookie", initial.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", initial.csrf()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("identifier", username, "password", CapacityDataGenerator.PASSWORD))));
        var loggedIn = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loggedIn.statusCode());
        String cookie = cookie(loggedIn);
        var token = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/csrf")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Session(cookie, json.readTree(token.body()).at("/data/token").asText());
    }

    private String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")).findFirst().orElseThrow().split(";", 2)[0];
    }

    private JsonNode send(Session session, String method, String path, Object body, int status) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30)).header("Cookie", session.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), path);
        return json.readTree(response.body()).path("data");
    }

    @Override
    public void close() throws IOException {
        client.close();
        measurements.stop();
        progress.put("timings", measurements.report());
        progress.put("modelsReachedInCurrentRound", modelStarts.values().stream().filter(value -> value > 0).count());
        progress.put("status", Boolean.TRUE.equals(progress.get("passed")) ? "passed" : "failed");
        Files.writeString(Path.of("target/p09-execution-capacity-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(progress));
    }
}
