package com.stonewu.agenteam.support.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.test.execution.AgentRunFixtureMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.sun.net.httpserver.HttpExchange;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.LinkedCaseInsensitiveMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 两版共用可控模型故障与重试计数断言；发行特有变更由调用用例提供。 */
public abstract class ScheduleRetryTestSupport extends ExecutionApiTestSupport {
    @Autowired
    protected ScheduleActionWorker actionWorker;

    protected void verifyTransientRetryCounts(int timeoutSeconds, Runnable afterFirstAttempt) throws Exception {
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", timeoutSeconds));
        allowModelCalls = true;
        var requests = new CopyOnWriteArrayList<JsonNode>();
        modelResponse = exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            if (requests.size() <= 2) {
                error(exchange, 503);
            } else {
                frame(exchange, Map.of("content", "第三次尝试的真实结果"), "stop");
            }
        };
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        attempt(run, 2);
        assertDelay(run, 30);
        assertEquals(0, count("run_checkpoint"));
        assertEquals(1, used());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification"))));
        afterFirstAttempt.run();
        ready(run);
        attempt(run, 3);
        assertDelay(run, 120);
        assertEquals(1, used());
        ready(run);
        finish(run);
        assertEquals(3, requests.size());
        assertEquals(1, used());
        assertEquals(0, reserved());
        assertEquals(1, count("quota_entry"));
        var attempts = data(mvc.perform(get(base() + "/runs/" + run + "/attempts").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(3, attempts.size());
        assertEquals(List.of("failed", "failed", "completed"), attempts.findValuesAsText("status"));
        assertEquals(3, attempts.findValuesAsText("outputMessageId").stream().distinct().count());
        var snapshot = snapshot(accepted);
        assertEquals(4, snapshot.path("messages").size());
        assertTrue(snapshot.toString().contains("第三次尝试的真实结果"));
        assertEquals(List.of("completed", "failed", "failed", "completed"), StreamSupport.stream(snapshot.path("messages").spliterator(), false).map(message -> message.path("status").asText()).toList());
        for (var request : requests) {
            assertEquals(1, request.path("messages").findValuesAsText("role").stream().filter("user"::equals).count());
        }
        var eventList = events.after(enterprise, accepted.path("conversationId").asText(), 0, 1000);
        long sequence = 0;
        for (var event : eventList) {
            assertEquals(Long.toString(++sequence), event.sequence());
            schemas.validate("ExecutionEvent", json.valueToTree(event));
        }
        assertEquals(2, eventList.stream().filter(event -> event.type().equals("attempt.updated")).count());
        assertEquals(0, eventList.stream().filter(event -> event.type().equals("run.failed")).count());
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledOccurrenceRow>().select(ScheduledOccurrenceRow::getStatus).eq(ScheduledOccurrenceRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
    }

    protected JsonNode submitSchedule() throws Exception {
        return submitSchedule(2);
    }

    protected JsonNode submitSchedule(int maxRetries) throws Exception {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "尝试失败后继续");
        input.put("hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id());
        input.put("agentVersionId", version);
        input.put("inputText", "只处理本次固定要求");
        input.put("frequency", "daily");
        input.put("localDate", null);
        input.put("localTime", "09:00");
        input.put("weekdays", List.of());
        input.put("monthDay", null);
        input.put("timezone", "UTC");
        input.put("enabled", false);
        input.put("maxRetries", maxRetries);
        String id = data(write(base() + "/schedules", input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        var accepted = data(write(base() + "/schedules/" + id + "/run", null, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertTrue(accepted.path("runId").isNull());
        assertTrue(actionWorker.runOnce());
        var prepared = data(mvc.perform(get(base() + "/schedules/" + id).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("latestOccurrence");
        assertEquals(accepted.path("id"), prepared.path("id"));
        return prepared;
    }

    protected void attempt(String run, int expected) throws Exception {
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("queued") && attemptNo(run) == expected || state(run).equals("failed"));
        }
        assertEquals("queued", state(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
        assertEquals(expected, attemptNo(run));
    }

    protected void finish(String run) throws Exception {
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> List.of("completed", "failed").contains(state(run)));
        }
        assertEquals("completed", state(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
    }

    protected void assertDelay(String run, int seconds) {
        assertEquals(seconds, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AgentRunFixtureMapper.class).scheduleRetryApiAssertDelayObject(run)));
    }

    protected void ready(String run) {
        var now = Timestamp.from(Instant.now());
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getDedupeKey, ("run:" + run)).set(BackgroundJobRow::getAvailableAt, (now)));
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getId, (run)).set(AgentRunRow::getNextAttemptAt, (now)).set(AgentRunRow::getQueuedAt, (now)));
    }

    protected String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    protected int attemptNo(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getCurrentAttemptNo).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getCurrentAttemptNo()).toList());
    }

    protected int used() {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).scheduleRetryApiUsedObject(enterprise));
    }

    protected JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected void error(HttpExchange exchange, int status) throws IOException {
        byte[] body = json.writeValueAsBytes(Map.of("error", Map.of("message", "测试模型故障", "type", "test_error", "code", "test_error")));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    protected void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", UUID.randomUUID().toString(), "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

}
