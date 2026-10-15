package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 已交付后仍可在同一对话追问，旧执行收尾不能吞掉后续输入。 */
class CollaborationContinuationTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private ConversationQueryService queries;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct_reply", "direct_text", "direct_finishing", "group_reply"})
    void completedAnswerCanContinueWithoutCreatingAnotherTaskOrLosingHistory(String scenario) throws Exception {
        boolean direct = !scenario.equals("group_reply");
        if (direct) {
            externalChat = "oc_followup_" + UUID.randomUUID();
        }
        routing.route(enterprise, receive(direct, "original", "你有什么能力", null).getId());
        var first = startNext();
        complete(first, 1, "我能进行架构设计、代码审查，并整理设计文档。");
        var done = onlyTask();
        assertEquals("completed", done.getStatus());
        String previousArtifact = done.getFinalArtifactId();
        assertNotNull(previousArtifact);
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getTaskId, done.getId())
            .eq(ChatOutboundMessageRow::getKind, "completed").set(ChatOutboundMessageRow::getStatus, "sent")
            .set(ChatOutboundMessageRow::getPlatformMessageId, "sent-answer"));
        boolean finishing = scenario.equals("direct_finishing");
        if (!finishing) {
            lifecycle.finish(first.lease(), "completed", null, null);
            observer.observe(enterprise, link(first.run().id()).getId());
        }
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, done.getId())
            .set(CollaborationTaskRow::getDeadlineAt, Instant.now().minusSeconds(60)));
        if (scenario.equals("direct_text")) {
            var exhausted = json.createObjectNode().put("maxRuns", done.getRunCount()).put("maxWorks", 20)
                .put("maxHandoffs", 0).put("maxParallel", 2).put("deadlineMinutes", 60).put("approvalPolicy", "full_access");
            databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, done.getId())
                .set(CollaborationTaskRow::getPolicySnapshot, exhausted.toString()));
        }
        var followup = receive(direct, "followup", "简短一点", scenario.equals("direct_text") ? null : "sent-answer");
        routing.route(enterprise, followup.getId());
        routing.route(enterprise, followup.getId());
        var resumed = onlyTask();
        assertEquals(done.getId(), resumed.getId());
        assertEquals(2, resumed.getInputRevision());
        assertEquals("active", resumed.getStatus());
        assertNull(resumed.getFinishedAt());
        assertNull(resumed.getFinalArtifactId());
        assertTrue(resumed.getDeadlineAt().isAfter(Instant.now()));
        assertEquals(done.getRunCount(), resumed.getRunCount(), "继续对话不能改写已发生的执行计数");
        if (scenario.equals("direct_text")) {
            assertEquals(0, json.readTree(resumed.getPolicySnapshot()).path("maxHandoffs").asInt());
        }
        if (finishing) {
            assertEquals("completed", work(done.getOwnerWorkId()).getStatus());
            lifecycle.finish(first.lease(), "completed", null, null);
            observer.observe(enterprise, link(first.run().id()).getId());
        }
        assertEquals("ready", work(done.getOwnerWorkId()).getStatus());
        var next = startNext();
        assertEquals(first.run().conversationId(), next.run().conversationId());
        String context = next.run().executionConfig().path("sourceContext").asText();
        assertTrue(context.contains("你有什么能力"));
        assertTrue(context.contains("简短一点"));
        assertTrue(context.contains(previousArtifact));
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        assertEquals(List.of("你有什么能力", "简短一点"), queries.snapshot(actor, next.run().conversationId()).messages().stream()
            .filter(message -> message.role().equals("user")).map(message -> message.content()).toList());
        complete(next, 2, "我能做架构设计和代码审查。");
        assertEquals("completed", onlyTask().getStatus());
        assertEquals(2, outgoing("completed").size());
        assertNotEquals(previousArtifact, onlyTask().getFinalArtifactId());
        lifecycle.finish(next.lease(), "completed", null, null);
        observer.observe(enterprise, link(next.run().id()).getId());
        assertEquals(1, taskCount());
    }

    @Test
    void explicitNewTaskDoesNotResumeTheQuotedCompletedTask() {
        externalChat = "oc_new_followup_" + UUID.randomUUID();
        routing.route(enterprise, receive(true, "original", "介绍能力", null).getId());
        var first = startNext();
        complete(first, 1, "我能设计架构。");
        var original = onlyTask();
        lifecycle.finish(first.lease(), "completed", null, null);
        observer.observe(enterprise, link(first.run().id()).getId());
        var next = receive(true, "new-task", "新任务：整理另一份文档", "original");
        routing.route(enterprise, next.getId());
        assertEquals(2, taskCount());
        assertNotEquals(original.getId(), messages().selectById(next.getId()).getTaskId());
        assertEquals("completed", databaseAccess.mapper(CollaborationTaskMapper.class).selectById(original.getId()).getStatus());
        var second = startNext();
        complete(second, 1, "另一份文档已整理。");
        lifecycle.finish(second.lease(), "completed", null, null);
        observer.observe(enterprise, link(second.run().id()).getId());
        String secondTask = messages().selectById(next.getId()).getTaskId();
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, "completed")
            .set(ChatOutboundMessageRow::getPlatformThreadId, "shared-topic"));
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getTaskId, secondTask)
            .eq(ChatOutboundMessageRow::getKind, "completed").set(ChatOutboundMessageRow::getPlatformMessageId, "second-answer")
            .set(ChatOutboundMessageRow::getStatus, "sent"));
        var quoted = receive(true, "quoted-new-task", "再简短一点", "second-answer");
        messages().update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getId, quoted.getId()).set(ChatMessageRow::getThreadId, "shared-topic"));
        routing.route(enterprise, quoted.getId());
        assertEquals(secondTask, messages().selectById(quoted.getId()).getTaskId(), "明确回复的消息优先于话题中的其他任务");
        assertEquals(2, taskCount());
    }

    @Test
    void cancelledTaskAndAnotherMemberCannotResumeByReply() {
        startTask("只有原发起人可以继续");
        var first = startNext();
        complete(first, 1, "已经完成。");
        var task = onlyTask();
        var other = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertThrows(ApiException.class, () -> inputs.append(other, task.getId(), task.getRevision(), "foreign", "继续", null));
        assertEquals("completed", onlyTask().getStatus());
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        assertEquals("COLLABORATION_TASK_CLOSED", assertThrows(ApiException.class,
            () -> inputs.cancel(actor, task.getId(), task.getRevision(), "cancel-finished", "不能取消已经完成的交付")).code());
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, task.getId())
            .set(CollaborationTaskRow::getStatus, "cancelled"));
        assertEquals("COLLABORATION_TASK_CLOSED", assertThrows(ApiException.class,
            () -> inputs.append(actor, task.getId(), task.getRevision(), "resume-cancelled", "继续", null)).code());
        assertEquals("cancelled", onlyTask().getStatus());
    }

    private void complete(Active active, int revision, String text) {
        var result = invoke(active, "finish-" + revision, "collab_complete_task", Map.of("expectedInputRevision", revision,
            "summary", text, "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        assertFalse(result.path("isError").asBoolean(), result::toString);
    }

    private ChatMessageRow receive(boolean direct, String id, String text, String reply) {
        var event = message(id, id, "user", text);
        var value = event.withObject("/event/message");
        if (direct) {
            value.put("chat_type", "p2p").remove("mentions");
        }
        if (reply != null) {
            value.put("parent_id", reply);
        }
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getPlatformMessageId, id));
    }

    private List<ChatOutboundMessageRow> outgoing(String kind) {
        return databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, kind));
    }
}
