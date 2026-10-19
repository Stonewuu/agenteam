package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireApplicationSqlMapper;
import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.agent.AgentChatHireService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.collaboration.CollaborationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 在隔离企业中验证首次使用、群内问答及真实用户正文，所有平台身份均为合成资料。 */
class CollaborationChatFlowTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private AgentChatHireService chatHires;
    @Autowired private ConversationQueryService queries;
    @Autowired private CollaborationQueryService collaborationQueries;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);

        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void automaticHireHappensAtExecutionAndDoesNotHireUnusedRoles() {
        removeHire();
        databaseAccess.mapper(AgentListingMapper.class).save(enterprise, agent, true, "approval", admin, Instant.now());
        var message = receive("approval", "请核对考勤");
        assertEquals("AGENT_CHAT_APPROVAL_REQUIRED", assertThrows(ApiException.class, () -> routing.route(enterprise, message.getId())).code());
        assertTrue(hires.forAgent(enterprise, initiator.id(), agent, false).isEmpty());
        assertEquals(0, databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>()
            .eq(AgentHireRequestRow::getEnterpriseId, enterprise).eq(AgentHireRequestRow::getUserId, initiator.id())));
        databaseAccess.mapper(AgentListingMapper.class).save(enterprise, agent, true, "automatic", admin, Instant.now());
        String financeAgent = separateFinance(initiator.id());
        startTask("先由人事核对考勤");
        assertTrue(hires.forAgent(enterprise, initiator.id(), agent, false).isEmpty());
        assertTrue(hires.forAgent(enterprise, initiator.id(), financeAgent, false).isEmpty());
        var active = startNext();
        assertEquals("full_access", active.run().executionConfig().path("approvalPolicy").asText());
        assertEquals("active", hires.forAgent(enterprise, initiator.id(), agent, false).orElseThrow().status());
        assertTrue(hires.forAgent(enterprise, initiator.id(), financeAgent, false).isEmpty());
        var result = invoke(active, "assign-finance", "collab_assign", Map.of("expectedInputRevision", 1,
            "targetRoleId", financeRole().getId(), "goal", "核对工资", "acceptanceCriteria", "核对结果", "artifactRefs", List.of(), "dependsOnWorkIds", List.of()));
        assertFalse(result.toString().contains("error"), result.toString());
        var next = startNext();
        assertEquals("full_access", next.run().executionConfig().path("approvalPolicy").asText());
        assertEquals(financeRole().getAgentVersionId(), next.run().agentVersionId());
        assertEquals("active", hires.forAgent(enterprise, initiator.id(), financeAgent, false).orElseThrow().status());
    }

    @Test
    void concurrentFirstUseCreatesOneHire() throws Exception {
        removeHire();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> chatHires.ensure(actor, agent));
            var second = pool.submit(() -> chatHires.ensure(actor, agent));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>()
            .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getUserId, initiator.id()).eq(AgentHireRow::getAgentId, agent)));
    }

    @Test
    void personalStopIsNotAutomaticallyReversed() {
        for (String status : List.of("paused", "terminated")) {
            databaseAccess.mapper(AgentHireSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRow>()
                .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getUserId, initiator.id()).eq(AgentHireRow::getAgentId, agent)
                .set(AgentHireRow::getStatus, status));
            var message = receive("stopped-hire-" + status, "请继续工作");
            assertEquals("AGENT_CHAT_HIRE_" + status.toUpperCase(), assertThrows(ApiException.class, () -> routing.route(enterprise, message.getId())).code());
            assertEquals(status, hires.forAgent(enterprise, initiator.id(), agent, false).orElseThrow().status());
            assertEquals(0, taskCount());
        }
    }

    @Test
    void unrequestedUnauthorizedRoleDoesNotPreventAuthorizedEntry() {
        String financeAgent = separateFinance(admin);
        startTask("人事先核对考勤");
        assertNotNull(startNext());
        assertTrue(hires.forAgent(enterprise, initiator.id(), financeAgent, false).isEmpty());
    }

    @Test
    void questionAndOptionsReturnToOriginalThreadAndReplyResumesSameTaskOnce() {
        var original = receive("question-original", "统计考勤");
        routing.route(enterprise, original.getId());
        var active = startNext();
        var args = Map.<String, Object>of("expectedInputRevision", 1, "question", "需要统计哪个月份？", "options", List.of("八月", "九月"));
        invoke(active, "question", "collab_ask_user", args);
        invoke(active, "question", "collab_ask_user", args);
        var notices = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getKind, "waiting_input"));
        assertEquals(1, notices.size());
        var notice = notices.getFirst();
        var payload = cipher.decrypt(enterprise, "outbound", notice.getId(), notice.getPayloadEncrypted());
        assertEquals("question-original", payload.path("replyToMessageId").asText());
        assertTrue(payload.path("replyInThread").asBoolean());
        assertTrue(payload.at("/content/text").asText().contains("需要统计哪个月份？\n1. 八月\n2. 九月"));
        assertTrue(approvals.forRun(enterprise, active.run().id()).isEmpty());
        lifecycle.finish(active.lease(), "completed", null, null);
        observer.observe(enterprise, link(active.run().id()).getId());
        assertEquals("waiting_input", onlyTask().getStatus());
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getId, notice.getId()).eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
            .set(ChatOutboundMessageRow::getStatus, "sent").set(ChatOutboundMessageRow::getPlatformMessageId, "question-sent"));
        var event = message("answer-event", "answer", "user", "九月");
        event.withObject("/event/message").put("parent_id", "question-sent");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        var answer = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getPlatformMessageId, "answer"));
        routing.route(enterprise, answer.getId());
        routing.route(enterprise, answer.getId());
        assertEquals(1, taskCount());
        assertEquals(2, onlyTask().getInputRevision());
        var resumed = startNext();
        assertEquals("full_access", resumed.run().executionConfig().path("approvalPolicy").asText());
        assertEquals(active.run().conversationId(), resumed.run().conversationId());
        assertEquals(2, link(resumed.run().id()).getRunRound());
        assertTrue(resumed.run().executionConfig().path("sourceContext").asText().contains("九月"));
    }

    @Test
    void realUserTextAndMentionsStaySeparateFromInternalAssignmentIncludingOldMessages() throws Exception {
        String text = "@_user_1 本轮工作：这是用户自己写的要求。";
        var event = message("clean-transcript", "clean-transcript", "user", text);
        ((ObjectNode) event.at("/event/message/mentions/0")).put("key", "@_user_1");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        var active = startNext();
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        String expected = "@人事 本轮工作：这是用户自己写的要求。";
        var snapshot = queries.snapshot(actor, active.run().conversationId());
        var input = snapshot.messages().stream().filter(value -> value.role().equals("user")).findFirst().orElseThrow();
        assertEquals(expected, input.content());
        assertTrue(active.run().executionConfig().path("sourceContext").asText().contains("assignment"));
        var stored = databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectById(input.id());
        String oldText = text + "\n\n本轮工作：\n内部安排内容";
        var blocks = json.readTree(stored.getBlocksJson());
        blocks.forEach(block -> {
            if (block.path("type").asText().equals("text")) {
                ((ObjectNode) block).put("text", oldText);
            }
        });
        databaseAccess.mapper(ExecutionMessageSqlMapper.class).update(new LambdaUpdateWrapper<AgentMessageRow>()
            .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getId, input.id())
            .set(AgentMessageRow::getContent, oldText).set(AgentMessageRow::getBlocksJson, blocks.toString()));
        var displayed = queries.history(actor, active.run().conversationId(), null, 30).messages().stream()
            .filter(value -> value.id().equals(input.id())).findFirst().orElseThrow();
        assertEquals(expected, displayed.content());
        assertTrue(displayed.blocks().stream().filter(block -> block.type().equals("text")).allMatch(block -> expected.equals(block.text())));
        assertEquals(oldText, databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectById(input.id()).getContent());
        var task = onlyTask();
        var requirements = (ObjectNode) cipher.decrypt(enterprise, "task-objective", task.getId(), task.getObjectiveEncrypted());
        requirements.put("text", text);
        String original = cipher.encrypt(enterprise, "task-objective", task.getId(), requirements);
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, task.getId())
            .set(CollaborationTaskRow::getObjectiveEncrypted, original));
        assertEquals(expected, collaborationQueries.list(actor, null, null, null, 30).items().getFirst().title());
        var detail = collaborationQueries.detail(actor, task.getId());
        assertEquals(expected, detail.task().title());
        assertEquals(expected, detail.requirements().get("text"));
        assertEquals(json.readTree(original), json.readTree(onlyTask().getObjectiveEncrypted()));
    }

    private void removeHire() {
        databaseAccess.mapper(AgentHireSqlMapper.class).delete(new LambdaQueryWrapper<AgentHireRow>()
            .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getUserId, initiator.id()).eq(AgentHireRow::getAgentId, agent));
    }

    private String separateFinance(String owner) {
        var resource = databaseAccess.mapper(ResourceSqlMapper.class).selectById(agent);
        resource.setId(UUID.randomUUID().toString());
        resource.setName("独立财务岗位");
        resource.setOwnerUserId(owner);
        resource.setPublishedVersionId(null);
        databaseAccess.mapper(ResourceSqlMapper.class).insert(resource);
        var fixed = databaseAccess.mapper(ResourceVersionSqlMapper.class).selectById(version);
        fixed.setId(UUID.randomUUID().toString());
        fixed.setResourceId(resource.getId());
        databaseAccess.mapper(ResourceVersionSqlMapper.class).insert(fixed);
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterprise)
            .eq(ResourceRow::getId, resource.getId()).set(ResourceRow::getPublishedVersionId, fixed.getId()));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).eq(ChatBotBindingRow::getId, finance.getId())
            .set(ChatBotBindingRow::getAgentId, resource.getId()).set(ChatBotBindingRow::getPinnedVersionId, fixed.getId()));
        return resource.getId();
    }
}
