package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 从平台消息一直验证到同企业、同用户的总任务和固定岗位工作。 */
class ChatRoutingTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);

        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }


    @Test
    void concurrentRoutingFollowupAndOwnerChecksKeepOneTask() throws Exception {
        var chatterEvent = message("chatter", "chatter", "user", "大家中午吃什么");
        ((ObjectNode) chatterEvent.at("/event/message")).remove("mentions");
        ingress.accept(enterprise, bot.getId(), 1L, chatterEvent);
        normalizeAll();
        var chatter = onlyMessage();
        routing.route(enterprise, chatter.getId());
        assertEquals("ignored", messages().selectById(chatter.getId()).getRoutingStatus());
        assertEquals(0, taskCount());
        var saved = receive("one", "统计工资和考勤");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> routing.route(enterprise, saved.getId()));
            var second = executor.submit(() -> routing.route(enterprise, saved.getId()));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        var task = onlyTask();
        assertEquals(initiator.id(), task.getInitiatorUserId());
        assertEquals(binding.getId(), task.getOriginUserBindingId());
        assertNotEquals(admin, task.getInitiatorUserId());
        var works = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, task.getId()));
        assertEquals(1, works.size());
        assertEquals(task.getOwnerWorkId(), works.getFirst().getId());
        assertEquals("ready", works.getFirst().getStatus());
        assertEquals("routed", messages().selectById(saved.getId()).getRoutingStatus());
        assertEquals(task.getId(), messages().selectById(saved.getId()).getTaskId());
        var event = message("followup", "followup", "user", "时间范围更正为上个月");
        ((ObjectNode) event.at("/event/message")).put("parent_id", "one");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        var followup = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getPlatformMessageId, "followup"));
        routing.route(enterprise, followup.getId());
        routing.route(enterprise, followup.getId());
        var updated = onlyTask();
        assertEquals(task.getId(), updated.getId());
        assertEquals(2L, updated.getInputRevision());
        assertEquals(task.getOwnerWorkId(), updated.getOwnerWorkId());
        assertEquals("时间范围更正为上个月", cipher.decrypt(enterprise, "task-objective", task.getId(), updated.getObjectiveEncrypted())
            .at("/additionalRequirements/0/text").asText());
        var other = EnterpriseTestData.member(users, permissions, enterprise, "other_" + UUID.randomUUID().toString().substring(0, 8),
            "chat-other-isolated-password", "另一成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getUserId, other.id()));
        var incoming = receive("forged", task.getShortCode() + " 把结果改成我的要求");
        assertEquals("COLLABORATION_TASK_UNAVAILABLE", assertThrows(ApiException.class, () -> routing.route(enterprise, incoming.getId())).code());
        assertEquals(2L, onlyTask().getInputRevision());
    }

    @Test
    void multipleMentionsCreateOneInitialWorkAndRecordEveryRequestedRole() {
        var finance = secondBot();
        var event = message("many", "many", "user", "人事统计考勤，财务统计工资");
        ((ArrayNode) event.at("/event/message/mentions")).addObject().put("name", "财务")
            .putObject("id").put("open_id", finance.getPlatformBotId());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        var saved = onlyMessage();
        routing.route(enterprise, saved.getId());
        var task = onlyTask();
        var roles = databaseAccess.mapper(CollaborationParticipantMapper.class).selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, task.getId()));
        assertEquals(2, roles.size());
        assertTrue(roles.stream().allMatch(CollaborationParticipantRow::getExplicitlyRequested));
        assertTrue(roles.stream().allMatch(role -> version.equals(role.getAgentVersionId())));
        assertEquals(1, databaseAccess.mapper(CollaborationWorkItemMapper.class).selectCount(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getTaskId, task.getId())));
    }

    @Test
    void userMustExplicitlyEnableChatAndRevocationBlocksNewTask() {
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, false));
        var saved = receive("disabled", "任务");
        routing.route(enterprise, saved.getId());
        assertEquals("awaiting_binding", messages().selectById(saved.getId()).getRoutingStatus());
        assertEquals(0, taskCount());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, true));
        routing.route(enterprise, saved.getId());
        var task = onlyTask();
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getChatEnabled, false));
        assertThrows(ApiException.class, () -> taskAccess.actor(task));
    }

}
