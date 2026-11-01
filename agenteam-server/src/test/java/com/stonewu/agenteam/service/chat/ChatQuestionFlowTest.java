package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatSendLease;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunObserver;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.execution.RunQuestionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.TestOutputPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 从接收群消息到提问卡片回调，回答后同一岗位执行继续且只完成一次。 */
class ChatQuestionFlowTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationDispatchService dispatch;
    @Autowired private CollaborationRunObserver observer;
    @Autowired private RunMapper runs;
    @Autowired private RunQuestionMapper questions;
    @Autowired private ChatCardInteractionTransactions cards;
    @Autowired private ChatQuestionReplyService replies;
    @Autowired private RunQuestionService questionService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"option", "form", "reply", "direct", "free_text", "cancel", "wecom_parts", "wecom_multiple"})
    void answersFromOptionsFormsAndReplyMessagesContinueTheOriginalRun(String mode) throws Exception {
        boolean wecom = mode.startsWith("wecom_");
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getResultPolicy, "full_allowed"));
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            Map<String, Object> delta;
            boolean first = iteration.getAndIncrement() == 0;
            if (first) {
                var calls = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < (mode.equals("wecom_multiple") ? 2 : 1); index++) {
                    calls.add(Map.of("index", index, "id", "chat-question-" + index, "type", "function", "function",
                        Map.of("name", modelTool(request, "ask_user"), "arguments", json.writeValueAsString(Map.of("question",
                            wecom ? "问题" + index + "：" + "请核对用途及阅读对象。".repeat(150) : "请选择报告形式",
                            "options", List.of(Map.of("label", "简短摘要", "description", "先给结论", "recommended", true), Map.of("label", "详细报告")))))));
                }
                delta = Map.of("tool_calls", calls);
            } else {
                assertTrue(request.path("messages").toString().contains("简短摘要"));
                delta = Map.of("content", "已经按你的选择生成简短摘要。");
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var chunk = Map.of("id", "chat-question-model", "choices", List.of(Map.of("index", 0, "delta", delta,
                "finish_reason", first ? "tool_calls" : "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        var message = receive(UUID.randomUUID().toString(), "请准备报告，先问我需要的形式");
        routing.route(enterprise, message.getId());
        if (wecom) {
            databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
                .eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getPlatformCode, "wecom_aibot"));
            databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
                .eq(EnterpriseIntegrationRow::getId, bot.getConnectionId()).set(EnterpriseIntegrationRow::getProviderCode, "wecom_aibot"));
        }
        var accepted = dispatch.submit(dispatch.claim("question-test").orElseThrow());
        String run = accepted.runId();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> "waiting_input".equals(runs.find(enterprise, run, false).orElseThrow().status()) || runs.find(enterprise, run, false).orElseThrow().terminal());
        }
        assertEquals("waiting_input", runs.find(enterprise, run, false).orElseThrow().status());
        assertEquals("waiting_input", onlyTask().getStatus());
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        var source = outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, "user_question_card")
            .orderByAsc(ChatOutboundMessageRow::getTaskSequence)).getFirst();
        for (var row : outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).orderByAsc(ChatOutboundMessageRow::getTaskSequence))) {
            markSent(row);
        }
        if (!wecom) {
            var invalid = callback(source, "wrong-chat", "question_option_0");
            invalid.withObject("/event/context").put("open_chat_id", "another-group");
            assertThrows(ApiException.class, () -> accept(invalid));
            var stranger = callback(source, "wrong-user", "question_option_0");
            stranger.withObject("/event/operator").put("open_id", "another-user");
            assertThrows(ApiException.class, () -> accept(stranger));
        }
        assertEquals("pending", questions.forRun(enterprise, run).getFirst().getStatus());
        if (mode.equals("direct")) {
            var actor = new AuthContext(initiator, enterprise, Set.copyOf(permissions.listPermissionCodes(initiator.id(), enterprise)));
            assertEquals(onlyTask().getId(), replies.pendingTask(actor, group.getId(), bot.getId(), Instant.now()).orElseThrow());
            assertTrue(replies.pendingTask(actor, "another-group", bot.getId(), Instant.now()).isEmpty());
            assertTrue(replies.pendingTask(actor, group.getId(), "another-bot", Instant.now()).isEmpty());
            assertTrue(replies.pendingTask(actor, group.getId(), bot.getId(), questions.forRun(enterprise, run).getFirst().getCreatedAt().minusSeconds(1)).isEmpty());
            var other = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
            assertTrue(replies.pendingTask(other, group.getId(), bot.getId(), Instant.now()).isEmpty());
        }
        if (mode.equals("cancel")) {
            lifecycle.cancel(new AuthContext(initiator, enterprise, Set.copyOf(permissions.listPermissionCodes(initiator.id(), enterprise))), run);
            var cancelledLink = databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
                .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, run));
            observer.observe(enterprise, cancelledLink.getId());
            assertEquals("cancelled", questions.forRun(enterprise, run).getFirst().getStatus());
            assertEquals("blocked", onlyTask().getStatus());
            assertThrows(ApiException.class, () -> accept(callback(source, "late-answer", "question_option_0")));
            return;
        }
        if (wecom) {
            answerQuestionParts(run);
        } else if (Set.of("reply", "direct", "free_text").contains(mode)) {
            if (mode.equals("free_text")) {
                var selected = accept(callback(source, "select-free", "question_free_text"));
                assertEquals("info", selected.at("/toast/type").asText());
                assertTrue(selected.at("/toast/content").asText().contains("请 @ 我"));
                assertEquals("waiting_input", runs.find(enterprise, run, false).orElseThrow().status());
            }
            var event = message("answer-event", "answer-message", "user", "简短摘要");
            if (mode.equals("reply")) {
                event.withObject("/event/message").put("parent_id", "om_" + source.getId());
            }
            ingress.accept(enterprise, bot.getId(), 1L, event);
            normalizeAll();
            var reply = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>()
                .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getPlatformMessageId, "answer-message"));
            routing.route(enterprise, reply.getId());
        } else {
            var event = callback(source, "answer-event", mode.equals("form") ? "question_submit" : "question_option_0");
            if (mode.equals("form")) {
                event.withObject("/event/action/form_value").put("answer", "简短摘要");
            }
            assertEquals("success", accept(event).at("/toast/type").asText());
            assertEquals("success", accept(event).at("/toast/type").asText());
        }
        assertEquals("queued", runs.find(enterprise, run, false).orElseThrow().status());
        assertEquals(1L, onlyTask().getInputRevision());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, run, false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, run, false).orElseThrow().status());
        var link = databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, run));
        observer.observe(enterprise, link.getId());
        assertEquals("completed", onlyTask().getStatus());
        assertEquals(1, onlyTask().getRunCount());
    }

    private void answerQuestionParts(String run) {
        var actor = new AuthContext(initiator, enterprise, Set.copyOf(permissions.listPermissionCodes(initiator.id(), enterprise)));
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        var all = outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .in(ChatOutboundMessageRow::getKind, "user_question_text", "user_question_card").orderByAsc(ChatOutboundMessageRow::getTaskSequence));
        var views = questionService.list(actor, run);
        for (var view : views.reversed()) {
            var parts = all.stream().filter(row -> "user_question_text".equals(row.getKind())
                && view.id().equals(cipher.decrypt(enterprise, "outbound-rendered", row.getId(), row.getRenderedPayloadEncrypted())
                    .path("questionReference").path("id").asText())).toList();
            assertTrue(parts.size() > 1);
            String joined = parts.stream().map(row -> cipher.decrypt(enterprise, "outbound-rendered", row.getId(), row.getRenderedPayloadEncrypted())
                .path("content").path("text").asText()).reduce("", String::concat);
            assertEquals(ChatQuestionMessages.text(view, true), joined);
            var card = all.stream().filter(row -> "user_question_card".equals(row.getKind())
                && view.id().equals(cipher.decrypt(enterprise, "outbound-rendered", row.getId(), row.getRenderedPayloadEncrypted())
                    .path("userQuestion").path("id").asText())).findFirst().orElseThrow();
            assertEquals(parts.getLast().getId(), card.getDependsOnOutboundId());
            var reply = new ChatMessageRow();
            reply.setConversationId(group.getId()); reply.setReceivedAt(Instant.now());
            if (questions.forRun(enterprise, run).stream().filter(row -> "pending".equals(row.getStatus())).count() > 1) {
                assertEquals("QUESTION_SELECTION_REQUIRED", assertThrows(ApiException.class,
                    () -> replies.answerText(actor, onlyTask().getId(), bot.getId(), reply, "简短摘要")).code());
                questionService.selectFreeText(actor, views.getFirst().id());
                reply.setReceivedAt(Instant.now());
            }
            reply.setReplyToPlatformId("om_" + parts.getLast().getId());
            var other = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
            assertThrows(ApiException.class, () -> replies.answerText(other, onlyTask().getId(), bot.getId(), reply, "代替回答"));
            assertFalse(replies.answerText(actor, onlyTask().getId(), "another-bot", reply, "简短摘要"));
            assertTrue(replies.answerText(actor, onlyTask().getId(), bot.getId(), reply, "简短摘要"));
            assertEquals("answered", questions.find(enterprise, view.id()).orElseThrow().getStatus());
            assertTrue(replies.answerText(actor, onlyTask().getId(), bot.getId(), reply, "简短摘要"));
            if (questions.forRun(enterprise, run).stream().anyMatch(row -> "pending".equals(row.getStatus()))) {
                assertEquals("waiting_input", runs.find(enterprise, run, false).orElseThrow().status());
            }
        }
    }

    private void markSent(ChatOutboundMessageRow row) {
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        outgoing.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, row.getId())
            .set(ChatOutboundMessageRow::getStatus, "leased").set(ChatOutboundMessageRow::getLeaseOwner, "question-test")
            .set(ChatOutboundMessageRow::getLeaseVersion, 1L).set(ChatOutboundMessageRow::getLeaseUntil, Instant.now().plusSeconds(60)));
        var lease = new ChatSendLease(enterprise, row.getId(), 1L, "question-test");
        sending.begin(lease, sending.prepare(lease).payload());
        sending.complete(lease, new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(ChannelSendResult.Outcome.ACCEPTED, "0",
            "om_" + row.getId(), null, null, null, 200, "question-fixture"), null));
    }

    private ObjectNode callback(ChatOutboundMessageRow source, String eventId, String action) {
        var event = json.createObjectNode();
        event.putObject("header").put("event_id", eventId).put("app_id", bots.application(bot).externalAppId());
        var body = event.putObject("event");
        body.putObject("operator").put("open_id", "ou_user");
        body.putObject("context").put("open_chat_id", externalChat).put("open_message_id", "om_" + source.getId());
        body.putObject("action").putObject("value").put("action", action);
        return event;
    }

    private JsonNode accept(JsonNode event) {
        var timing = new ChatCallbackTiming(Clock.systemUTC());
        try {
            return cards.accept(enterprise, bot.getId(), 1L, bots.application(bot).credentialRevision(), bot.getRevision(), event, timing);
        } finally {
            var measured = timing.finish();
            var path = TestOutputPaths.path("chat-immediate", "feishu-callback-timings.jsonl");
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json.writeValueAsString(Map.of("event", event.at("/header/event_id").asText(),
                    "totalMillis", measured.totalMillis(), "stages", measured.stages())) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new UncheckedIOException("无法保存回调耗时测试结果", failure);
            }
        }
    }
}
