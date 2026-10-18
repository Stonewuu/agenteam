package com.stonewu.agenteam.controller.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.ConversationTitleJob;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.service.execution.ConversationTitleWorker;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 在同一隔离环境中验证自动唤醒、模型并行、人工名称与过期任务保护。 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationTitleApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private BackgroundJobSqlMapper jobs;
    @Autowired
    private ConversationSqlMapper conversations;
    @Autowired
    private RunMapper runs;
    @Autowired
    private ModelUsageRecordMapper usage;
    @Autowired
    private ConversationTitleWorker titles;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.titles.enabled", () -> "true");
    }

    @BeforeEach
    void allowBackgroundCalls() {
        allowModelCalls = true;
    }

    @AfterEach
    void cancelPendingTitles() {
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, ConversationTitleJob.KIND)
            .in(BackgroundJobRow::getStatus, List.of("queued", "leased"))
            .set(BackgroundJobRow::getStatus, "cancelled").set(BackgroundJobRow::getLeaseUntil, null));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        titles.close();
        ENVIRONMENT.close();
    }

    @Test
    void startsBothModelsWithoutBrowserSubscriptionAndSavesTitleDuringAnswer() throws Exception {
        var titleStarted = new CountDownLatch(1);
        var answerStarted = new CountDownLatch(1);
        var titleFinish = new CountDownLatch(1);
        var answerFinish = new CountDownLatch(1);
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            boolean title = request.path("messages").toString().contains("为用户提供的资料拟一个");
            (title ? titleStarted : answerStarted).countDown();
            waitFor(title ? titleFinish : answerFinish);
            reply(exchange, title ? "活动筹备安排" : "活动准备事项已经整理完成。");
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            String key = UUID.randomUUID().toString();
            var accepted = create(key);
            assertEquals(accepted, create(key));
            String conversation = accepted.path("conversationId").asText();
            assertEquals("请整理这次活动的准备事项。", conversations.selectById(conversation).getTitle());
            assertEquals(1, jobs.selectCount(titleJobs(conversation)));
            worker.poll();
            assertTrue(titleStarted.await(8, TimeUnit.SECONDS));
            assertTrue(answerStarted.await(8, TimeUnit.SECONDS));
            assertEquals("running", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status());
            titleFinish.countDown();
            await(() -> "completed".equals(job(conversation).getStatus()));
            var metadata = metadata(conversation);
            schemas.validate("ConversationMetadata", metadata.get(0));
            assertEquals("活动筹备安排", metadata.at("/0/conversation/title").asText());
            assertFalse(metadata.get(0).path("titlePending").asBoolean());
            assertEquals("running", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status());
            assertEquals(2, count("agent_message"));
            var titleUsage = usage.selectList(new LambdaQueryWrapper<ModelUsageRecordRow>()
                .eq(ModelUsageRecordRow::getEnterpriseId, enterprise).eq(ModelUsageRecordRow::getPurpose, "title"));
            assertEquals(1, titleUsage.size());
            assertEquals(conversation, titleUsage.getFirst().getConversationId());
            assertNull(titleUsage.getFirst().getRunId());
            assertNull(titleUsage.getFirst().getRunAttemptNo());
            assertNull(titleUsage.getFirst().getRunLeaseVersion());
            assertTrue(metadata(UUID.randomUUID().toString()).isEmpty());
            mvc.perform(get(base() + "/conversations/metadata").cookie(cookie).param("ids", new String[0]))
                .andExpect(status().is(422));
            mvc.perform(get(base() + "/conversations/metadata").cookie(cookie)
                .param("ids", Collections.nCopies(51, conversation).toArray(String[]::new)))
                .andExpect(status().is(422));
            mvc.perform(get(base() + "/conversations/metadata").param("ids", conversation))
                .andExpect(status().isUnauthorized());
            answerFinish.countDown();
            await(() -> "completed".equals(runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status()));
        } finally {
            titleFinish.countDown();
            answerFinish.countDown();
        }
    }

    @Test
    void manualNameWinsAndExpiredWorkerCannotOverwriteRecoveredResult() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            started.countDown();
            waitFor(finish);
            reply(exchange, "模型生成的名称");
        };
        var manual = create(UUID.randomUUID().toString());
        String conversation = manual.path("conversationId").asText();
        try {
            assertTrue(started.await(8, TimeUnit.SECONDS));
            var row = conversations.selectById(conversation);
            change(HttpMethod.PATCH, base() + "/conversations/" + conversation, Map.of("title", "我填写的名称"),
                row.getRevision().toString(), UUID.randomUUID().toString()).andExpect(status().isOk());
        } finally {
            finish.countDown();
        }
        await(() -> "cancelled".equals(job(conversation).getStatus()));
        assertEquals("我填写的名称", conversations.selectById(conversation).getTitle());
        assertFalse(metadata(conversation).get(0).path("titlePending").asBoolean());

        var firstStarted = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (attempts.incrementAndGet() == 1) {
                firstStarted.countDown();
                waitFor(releaseOld);
                reply(exchange, "旧进程结果");
            } else {
                reply(exchange, "恢复后的标题");
            }
        };
        var recovery = create(UUID.randomUUID().toString());
        String recoveredConversation = recovery.path("conversationId").asText();
        try {
            assertTrue(firstStarted.await(8, TimeUnit.SECONDS));
            var before = conversations.selectById(recoveredConversation);
            var expired = job(recoveredConversation);
            jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, expired.getId())
                .set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
            titles.wake();
            await(() -> "completed".equals(job(recoveredConversation).getStatus()));
            assertEquals("恢复后的标题", conversations.selectById(recoveredConversation).getTitle());
            assertEquals(before.getRevision() + 1, conversations.selectById(recoveredConversation).getRevision());
            assertEquals(before.getUpdatedAt(), conversations.selectById(recoveredConversation).getUpdatedAt());
            assertTrue(job(recoveredConversation).getLeaseVersion() > expired.getLeaseVersion());
        } finally {
            releaseOld.countDown();
        }
        assertEquals("恢复后的标题", conversations.selectById(recoveredConversation).getTitle());
    }

    @Test
    void titleFailureDoesNotFailAnswerAndTitleInsertFailureRollsBackSubmission() throws Exception {
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (request.path("messages").toString().contains("为用户提供的资料拟一个")) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            } else {
                reply(exchange, "正常回答完成。");
            }
        };
        var accepted = create(UUID.randomUUID().toString());
        String conversation = accepted.path("conversationId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> "completed".equals(runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status()));
        }
        for (int attempt = 1; attempt < 3; attempt++) {
            int completedAttempts = attempt;
            await(() -> "queued".equals(job(conversation).getStatus()) && job(conversation).getAttemptCount() == completedAttempts);
            jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, job(conversation).getId())
                .set(BackgroundJobRow::getAvailableAt, Instant.now()));
            titles.wake();
        }
        await(() -> "failed".equals(job(conversation).getStatus()));
        assertEquals("请整理这次活动的准备事项。", conversations.selectById(conversation).getTitle());
        assertFalse(metadata(conversation).get(0).path("titlePending").asBoolean());
        assertEquals(3, job(conversation).getAttemptCount());
        var administration = databaseAccess.mapper(TestDatabaseAdministrationMapper.class);
        administration.rejectTitleJobCreation();
        try {
            write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().is5xxServerError());
            assertEquals(1, count("agent_conversation"));
            assertEquals(1, count("agent_run"));
            assertEquals(2, count("agent_message"));
        } finally {
            administration.allowTitleJobCreation();
        }
    }

    private JsonNode create(String key) throws Exception {
        return data(write(base() + "/conversations", body(), key).andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode metadata(String id) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/metadata").cookie(cookie).param("ids", id))
            .andExpect(status().isOk()).andReturn());
    }

    private LambdaQueryWrapper<BackgroundJobRow> titleJobs(String conversation) {
        return new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, ConversationTitleJob.KIND)
            .eq(BackgroundJobRow::getDedupeKey, ConversationTitleJob.key(conversation));
    }

    private BackgroundJobRow job(String conversation) {
        return jobs.selectOne(titleJobs(conversation));
    }

    private void reply(HttpExchange exchange, String content) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String event = json.writeValueAsString(Map.of("id", "controlled-title-response", "choices",
                List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
            exchange.getResponseBody().write(("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private void waitFor(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IOException("模型测试未收到继续信号。");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("模型测试已被中断。", interrupted);
        }
    }
}
