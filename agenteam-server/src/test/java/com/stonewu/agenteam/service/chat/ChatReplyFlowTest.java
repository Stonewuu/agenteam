package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.service.collaboration.CollaborationGeneratedResultService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离企业、合成平台消息和模拟模型验证完整问答；不向真实飞书发送消息。 */
class ChatReplyFlowTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private CollaborationGeneratedResultService generatedResults;
    @Autowired private ConversationQueryService queries;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);

        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "manual_link", "manual_full"})
    void questionSupplementAndAnswerStayInOnePlaceWithoutDuplicateCompletion(String scenario) {
        boolean direct = scenario.equals("direct");
        boolean full = direct || scenario.equals("manual_full");
        if (direct) {
            externalChat = "oc_private_" + UUID.randomUUID();
        } else {
            conversations.update(users.findById(admin).orElseThrow(), enterprise, false, group.getId(),
                new ChatConversationUpdateRequest(group.getRevision(), "enabled", List.of(bot.getId()), "single_bot",
                    bot.getId(), bot.getId(), "mention", full ? "full_allowed" : "summary_link", Map.of()));
        }
        var original = receive(direct, "original", "帮我核对考勤");
        routing.route(enterprise, original.getId());
        assertEquals(full ? "chat_full" : "summary_link", onlyTask().getOutputVisibility());
        if (direct) {
            assertEquals(List.of("accepted"), outgoing().stream().map(ChatOutboundMessageRow::getKind).toList());
        }
        deliverAll(direct, original.getPlatformMessageId());
        var first = startNext();
        observer.observe(enterprise, link(first.run().id()).getId());
        deliverAll(direct, original.getPlatformMessageId());
        var questionArgs = Map.<String, Object>of("expectedInputRevision", 1, "question", "需要核对哪个月份？", "options", List.of("八月", "九月"));
        invoke(first, "question", "collab_ask_user", questionArgs);
        invoke(first, "question", "collab_ask_user", questionArgs);
        var questions = outgoing().stream().filter(row -> row.getKind().equals("waiting_input")).toList();
        assertEquals(1, questions.size());
        assertTrue(text(questions.getFirst()).contains("需要核对哪个月份？\n1. 八月\n2. 九月"));
        if (direct) {
            assertFalse(text(questions.getFirst()).contains(onlyTask().getShortCode()));
        }
        deliverAll(direct, original.getPlatformMessageId());
        lifecycle.finish(first.lease(), "completed", null, null);
        observer.observe(enterprise, link(first.run().id()).getId());
        String reply = direct ? "九月" : onlyTask().getShortCode() + " 九月";
        var answer = receive(direct, "supplement", reply);
        routing.route(enterprise, answer.getId());
        routing.route(enterprise, answer.getId());
        assertEquals(1, taskCount());
        assertEquals(2, onlyTask().getInputRevision());
        deliverAll(direct, original.getPlatformMessageId());
        var next = startNext();
        assertEquals(first.run().conversationId(), next.run().conversationId());
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        var displayed = queries.snapshot(actor, next.run().conversationId()).messages().stream()
            .filter(message -> message.role().equals("user")).toList();
        assertEquals(List.of("帮我核对考勤", reply), displayed.stream().map(message -> message.content()).toList());
        assertTrue(next.run().executionConfig().path("sourceContext").asText().contains("帮我核对考勤"));
        assertTrue(next.run().executionConfig().path("sourceContext").asText().contains("九月"));
        if (scenario.equals("manual_link")) {
            var storedMessage = databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectById(next.run().inputMessageId());
            String accumulated = "帮我核对考勤\n\n补充要求：\n" + reply;
            var blocks = json.createArrayNode();
            for (var block : queries.snapshot(actor, next.run().conversationId()).messages().stream()
                .filter(message -> message.id().equals(storedMessage.getId())).findFirst().orElseThrow().blocks()) {
                var storedBlock = (ObjectNode) json.valueToTree(block);
                if (block.type().equals("text")) {
                    storedBlock.put("text", accumulated);
                }
                blocks.add(storedBlock);
            }
            databaseAccess.mapper(ExecutionMessageSqlMapper.class).update(new LambdaUpdateWrapper<AgentMessageRow>()
                .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getId, storedMessage.getId())
                .set(AgentMessageRow::getContent, accumulated).set(AgentMessageRow::getBlocksJson, blocks.toString()));
            var historyMessage = queries.history(actor, next.run().conversationId(), null, 30).messages().stream()
                .filter(message -> message.id().equals(storedMessage.getId())).findFirst().orElseThrow();
            assertEquals(reply, historyMessage.content());
            assertTrue(historyMessage.blocks().stream().filter(block -> block.type().equals("text")).allMatch(block -> reply.equals(block.text())));
            assertEquals(accumulated, databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectById(storedMessage.getId()).getContent());
        }
        observer.observe(enterprise, link(next.run().id()).getId());
        String resultText = "九月实际出勤二十天，缺勤零天。";
        var completeArgs = Map.<String, Object>of("expectedInputRevision", 2, "summary", resultText,
            "artifactRefs", List.of(), "acceptedPriorResults", List.of());
        assertFalse(invoke(next, "complete", "collab_complete_task", completeArgs).path("isError").asBoolean());
        invoke(next, "complete", "collab_complete_task", completeArgs);
        lifecycle.finish(next.lease(), "completed", null, null);
        observer.observe(enterprise, link(next.run().id()).getId());
        deliverAll(direct, original.getPlatformMessageId());
        assertEquals("completed", onlyTask().getStatus());
        var completed = outgoing().stream().filter(row -> row.getKind().equals("completed")).toList();
        assertEquals(1, completed.size());
        String body = text(completed.getFirst());
        if (full) {
            assertEquals(resultText, body);
        } else {
            assertFalse(body.contains(resultText), "群聊没有公开许可时不得发送完整结果");
        }
        if (direct) {
            assertEquals(List.of("accepted", "waiting_input", "completed"), outgoing().stream().map(ChatOutboundMessageRow::getKind).toList());
        } else {
            assertTrue(outgoing().stream().anyMatch(row -> row.getKind().equals("input_added")));
            assertTrue(outgoing().stream().anyMatch(row -> row.getKind().equals("started")));
        }
        assertEquals(0, databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).eq(BackgroundJobRow::getKind, "notification")));
    }

    @ParameterizedTest
    @CsvSource({"false,3000", "true,18000", "false,33000"})
    void automaticAnswerKeepsFullTextOrDeliversCompleteFile(boolean direct, int characters) throws Exception {
        externalChat = "oc_answer_" + UUID.randomUUID();
        routing.route(enterprise, receive(direct, "long-answer", "说明你的能力").getId());
        assertEquals("chat_full", onlyTask().getOutputVisibility());
        deliverAll(direct, "long-answer");
        // 私聊接收人已经核对，即使会话设为仅链接，新任务仍能完整交付本人。
        if (direct) {
            databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
                .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, onlyTask().getConversationId())
                .set(ChatConversationRow::getResultPolicy, "summary_link"));
        }
        var submitted = dispatch.submit(dispatch.claim("answer-dispatch").orElseThrow());
        String answer = "合成测试内容。".repeat(characters / 7) + "最终结论。";
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var delta = Map.of("id", "answer", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("content", answer), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(delta) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, submitted.runId(), false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, submitted.runId(), false).orElseThrow().status());
        var link = link(submitted.runId());
        var prepared = generatedResults.prepare(link);
        observer.observe(enterprise, link.getId(), prepared);
        observer.observe(enterprise, link.getId(), prepared);
        assertEquals("completed", onlyTask().getStatus());
        String sentText = outgoing().stream().filter(row -> row.getKind().equals("completed"))
            .map(row -> text(row)).collect(Collectors.joining());
        if (characters < 32000) {
            assertEquals(answer, sentText, "不能把完整回答替换成两千字的成果摘要");
        } else {
            assertEquals("完整回复较长，已整理为文件，请查看附件。", sentText);
        }
        boolean hasFile = outgoing().stream().anyMatch(row -> row.getKind().equals("completed_file"));
        assertEquals(characters > 16000 / 3, hasFile);
        while (outgoing().stream().anyMatch(row -> row.getStatus().equals("pending"))) {
            var lease = sending.claim("answer-send").orElseThrow();
            var payload = sending.prepare(lease).payload();
            assertPlace(payload, direct, "long-answer");
            if (payload.path("messageType").asText().equals("file")) {
                try (var content = sending.uploadFile(lease).open().get()) {
                    assertEquals(answer, new String(content.readAllBytes(), StandardCharsets.UTF_8));
                }
                sending.uploaded(lease, "file_test_answer");
                assertPlace(sending.prepare(lease).payload(), direct, "long-answer");
            }
            sending.begin(lease);
            sending.complete(lease, accepted(lease.outboundId()));
        }
        assertTrue(outgoing().stream().allMatch(row -> row.getStatus().equals("sent")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingAutomaticGroupRepliesWithContentOnContinuationUnlessUserChoseLinks(boolean explicitlyLinkOnly) {
        externalChat = "oc_continued_answer_" + UUID.randomUUID();
        routing.route(enterprise, receive(false, "original", "你有哪些能力").getId());
        deliverAll(false, "original");
        var original = onlyTask();
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, original.getConversationId())
            .set(ChatConversationRow::getResultPolicy, "summary_link"));
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, original.getId())
            .set(CollaborationTaskRow::getOutputVisibility, "summary_link"));
        if (explicitlyLinkOnly) {
            var actor = identities.actor(enterprise, initiator.id(), binding.getId());
            inputs.append(actor, original.getId(), original.getRevision(), "link-choice", "只发送链接", null,
                json.createObjectNode().put("outputVisibility", "summary_link"));
        }
        long firstRevision = onlyTask().getInputRevision();
        var first = startNext();
        assertFalse(invoke(first, "finish-old", "collab_complete_task", Map.of("expectedInputRevision", firstRevision,
            "summary", "我能做架构设计和代码审查。", "artifactRefs", List.of(), "acceptedPriorResults", List.of())).path("isError").asBoolean());
        lifecycle.finish(first.lease(), "completed", null, null);
        observer.observe(enterprise, link(first.run().id()).getId());
        deliverAll(false, "original");
        var oldAnswer = outgoing().stream().filter(row -> row.getKind().equals("completed")).findFirst().orElseThrow();
        assertFalse(text(oldAnswer).contains("我能做架构设计"));
        var nextMessage = receive(false, "followup", "简短一点", oldAnswer.getPlatformMessageId());
        routing.route(enterprise, nextMessage.getId());
        routing.route(enterprise, nextMessage.getId());
        assertEquals("full_allowed", conversations.require(enterprise, original.getConversationId()).getResultPolicy());
        assertEquals(original.getId(), onlyTask().getId());
        assertEquals(firstRevision + 1, onlyTask().getInputRevision());
        assertEquals(explicitlyLinkOnly ? "summary_link" : "chat_full", onlyTask().getOutputVisibility());
        var next = startNext();
        assertEquals(first.run().conversationId(), next.run().conversationId());
        String answer = "我能做架构设计与代码审查。";
        assertFalse(invoke(next, "finish-new", "collab_complete_task", Map.of("expectedInputRevision", firstRevision + 1,
            "summary", answer, "artifactRefs", List.of(), "acceptedPriorResults", List.of())).path("isError").asBoolean());
        deliverAll(false, "original");
        var completed = outgoing().stream().filter(row -> row.getKind().equals("completed")).toList();
        assertEquals(2, completed.size());
        String body = text(completed.getLast());
        if (explicitlyLinkOnly) {
            assertFalse(body.contains(answer));
        } else {
            assertEquals(answer, body);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedFileAndAnswerUseTheSameDestination(boolean direct) throws Exception {
        if (direct) {
            externalChat = "oc_private_" + UUID.randomUUID();
        }
        routing.route(enterprise, receive(direct, "file-original", "生成考勤文件").getId());
        var task = onlyTask();
        if (!direct) {
            databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
                .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, task.getConversationId())
                .set(ChatConversationRow::getResultPolicy, "full_allowed"));
            databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, task.getId())
                .set(CollaborationTaskRow::getOutputVisibility, "chat_full"));
        }
        deliverAll(direct, "file-original");
        var active = startNext();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        String fileText = "合成员工 E001：20.5 天。";
        var file = generated.register(generated.collaborationText(actor, agent, version, active.run().id(), fileText));
        invoke(active, "publish", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "attendance",
            "title", "考勤文件", "summary", "合成考勤明细", "kind", "file", "fileId", file.id()));
        String answer = "已核对考勤。".repeat(250);
        var args = Map.<String, Object>of("expectedInputRevision", 1, "summary", answer,
            "artifactRefs", references(artifact("attendance")), "acceptedPriorResults", List.of());
        assertFalse(invoke(active, "finish", "collab_complete_task", args).path("isError").asBoolean());
        invoke(active, "finish", "collab_complete_task", args);
        assertEquals(1, outgoing().stream().filter(row -> row.getKind().equals("completed_file")).count());
        assertEquals(1, outgoing().stream().filter(row -> row.getKind().equals("completed")).count());
        assertEquals(answer, outgoing().stream().filter(row -> row.getKind().equals("completed"))
            .map(row -> text(row)).collect(Collectors.joining()));
        while (outgoing().stream().anyMatch(row -> row.getStatus().equals("pending"))) {
            var lease = sending.claim("file-send").orElseThrow();
            var payload = sending.prepare(lease).payload();
            assertPlace(payload, direct, "file-original");
            if (payload.path("messageType").asText().equals("file")) {
                try (var content = sending.uploadFile(lease).open().get()) {
                    assertEquals(fileText, new String(content.readAllBytes(), StandardCharsets.UTF_8));
                }
                sending.uploaded(lease, "file_test_attendance");
                assertPlace(sending.prepare(lease).payload(), direct, "file-original");
            }
            sending.begin(lease);
            sending.complete(lease, accepted(lease.outboundId()));
        }
    }

    @Test
    void privateReplyCannotBeSentAfterRecipientBindingChanges() {
        externalChat = "oc_private_" + UUID.randomUUID();
        routing.route(enterprise, receive(true, "binding-original", "你好").getId());
        var active = startNext();
        invoke(active, "finish", "collab_complete_task", Map.of("expectedInputRevision", 1,
            "summary", "你好，我可以帮你核对考勤。", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        var lease = sending.claim("binding-check").orElseThrow();
        assertPlace(sending.prepare(lease).payload(), true, "binding-original");
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, onlyTask().getConversationId())
            .set(ChatConversationRow::getDirectBindingId, null));
        assertEquals("CHAT_SEND_CANCELLED", assertThrows(ApiException.class, () -> sending.prepare(lease)).code());
    }

    private ChatMessageRow receive(boolean direct, String id, String text) {
        return receive(direct, id, text, null);
    }

    private ChatMessageRow receive(boolean direct, String id, String text, String reply) {
        var event = message(id, id, "user", text);
        if (direct) {
            event.withObject("/event/message").put("chat_type", "p2p").remove("mentions");
        }
        if (reply != null) {
            event.withObject("/event/message").put("parent_id", reply).remove("mentions");
        }
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getPlatformMessageId, id));
    }

    private List<ChatOutboundMessageRow> outgoing() {
        return databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).orderByAsc(ChatOutboundMessageRow::getTaskSequence, ChatOutboundMessageRow::getPartNo));
    }

    private JsonNode payload(ChatOutboundMessageRow row) {
        return cipher.decrypt(enterprise, "outbound", row.getId(), row.getPayloadEncrypted());
    }

    private String text(ChatOutboundMessageRow row) {
        var delivered = row.getRenderedPayloadEncrypted() == null ? payload(row)
            : cipher.decrypt(enterprise, "outbound-rendered", row.getId(), row.getRenderedPayloadEncrypted());
        return "interactive".equals(delivered.path("messageType").asText())
            ? delivered.at("/content/body/elements/0/content").asText() : delivered.at("/content/text").asText();
    }

    private void deliverAll(boolean direct, String origin) {
        while (outgoing().stream().anyMatch(row -> row.getStatus().equals("pending"))) {
            var lease = sending.claim("text-send").orElseThrow();
            assertPlace(sending.prepare(lease).payload(), direct, origin);
            sending.begin(lease);
            sending.complete(lease, accepted(lease.outboundId()));
        }
    }

    private ChatPlatformAdapter.SendReceipt accepted(String id) {
        return new ChatPlatformAdapter.SendReceipt(new ChannelSendResult(Outcome.ACCEPTED, "0", "om_" + id,
            null, null, null, 200, "synthetic-trace"), null);
    }

    private void assertPlace(JsonNode payload, boolean direct, String origin) {
        assertEquals(!direct, payload.path("replyInThread").asBoolean());
        assertEquals(direct ? "" : origin, payload.path("replyToMessageId").asText(""));
        assertEquals(externalChat, payload.path("receiveId").asText());
    }
}
