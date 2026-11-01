package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.service.agent.AgentImageInputService;
import com.stonewu.agenteam.service.agent.AgentInputService;
import com.stonewu.agenteam.service.collaboration.CollaborationMessageSources;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.workspace.WorkspaceInputs;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import io.agentscope.core.message.ImageBlock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 多轮图片与纯文字采用同一岗位会话，旧关联也不能把历史图片重复送到本轮。 */
@Import(CollaborationMessageAttachmentTest.Platform.class)
class CollaborationMessageAttachmentTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    @Autowired private ChatResourceWorker downloader;
    @Autowired private ChatResourceTransactions resources;
    @Autowired private FileInspectionWorker inspections;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private TransactionTemplate transaction;
    @Autowired private MessageAttachmentMapper attachments;
    @Autowired private ConversationQueryService queries;
    @Autowired private AgentInputService agentInputs;
    @Autowired private AgentImageInputService imageInputs;
    @Autowired private CollaborationMessageSources sources;
    @Autowired private WorkspaceInputs workspaceInputs;

    @TestConfiguration
    static class Platform {
        @Bean @Primary IntegrationEndpoints endpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @BeforeEach
    void fileFixture() {
        PLATFORM.reset();
        transaction.executeWithoutResult(status -> secrets.store(enterprise, bot.getConnectionId(), "synthetic-file-secret", admin, 1, Instant.now()));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENV.close();
    }

    @Test
    void messageAttachmentsFollowTheirOriginalInputWhileWorkingFilesRemainAvailable() throws Exception {
        String firstFile = picture("first-picture", null);
        var first = startNext();
        var firstInput = agentInputs.message(first.run());
        assertEquals(1, attachments.forMessages(enterprise, List.of(first.run().inputMessageId())).get(first.run().inputMessageId()).size());
        finish(first, 1);

        var plain = receive("plain-followup", onlyTask().getShortCode() + " 请简短说明");
        routing.route(enterprise, plain.getId());
        var second = startNext();
        assertEquals(first.run().conversationId(), second.run().conversationId());
        assertTrue(attachments.forMessages(enterprise, List.of(second.run().inputMessageId())).isEmpty());
        assertEquals(firstFile, second.run().executionConfig().at("/inputSourceFiles/0/id").asText());
        assertTrue(workspaceInputs.supplied(second.run(), second.run().conversationId(), Set.of()).contains(firstFile));
        var secondInput = agentInputs.message(second.run());
        assertTrue(secondInput.getTextContent().contains(WorkspaceInputs.inputPath(firstFile, "图片.png")));
        var actor = identities.actor(enterprise, initiator.id(), binding.getId());
        assertEquals(List.of(1, 0), queries.snapshot(actor, second.run().conversationId()).messages().stream()
            .filter(message -> "user".equals(message.role())).map(message -> message.attachments().size()).toList());
        assertEquals(List.of(1L, 0L), imageInputs.prepare(second.run(), List.of(firstInput, secondInput)).stream()
            .map(message -> message.getContent().stream().filter(ImageBlock.class::isInstance).count()).toList());

        // 模拟修复前的历史记录：原关系保留，读取时必须按原平台消息辨别本轮附件。
        transaction.executeWithoutResult(status -> attachments.bind(enterprise, second.run().inputMessageId(), List.of(firstFile), Instant.now()));
        assertEquals(List.of(1, 0), queries.snapshot(actor, second.run().conversationId()).messages().stream()
            .filter(message -> "user".equals(message.role())).map(message -> message.attachments().size()).toList());
        assertEquals(List.of(1L, 0L), imageInputs.prepare(second.run(), List.of(firstInput, secondInput)).stream()
            .map(message -> message.getContent().stream().filter(ImageBlock.class::isInstance).count()).toList());
        finish(second, 2);

        String secondFile = picture("second-picture", "plain-followup");
        assertNotEquals(firstFile, secondFile, "内容相同的两次上传也按各自原消息归属显示");
        var third = startNext();
        assertEquals(2, third.run().executionConfig().path("inputSourceFiles").size());
        assertEquals(List.of(secondFile), attachments.forMessages(enterprise, List.of(third.run().inputMessageId()))
            .get(third.run().inputMessageId()).stream().map(file -> file.get("id")).toList());
        var thirdInput = agentInputs.message(third.run());
        assertEquals(List.of(1, 0, 1), queries.snapshot(actor, third.run().conversationId()).messages().stream()
            .filter(message -> "user".equals(message.role())).map(message -> message.attachments().size()).toList());
        assertEquals(List.of(1L, 0L, 1L), imageInputs.prepare(third.run(), List.of(firstInput, secondInput, thirdInput)).stream()
            .map(message -> message.getContent().stream().filter(ImageBlock.class::isInstance).count()).toList());
        var requirements = cipher.decrypt(enterprise, "task-objective", onlyTask().getId(), onlyTask().getObjectiveEncrypted());
        assertTrue(sources.forInput(enterprise, admin, onlyTask().getId(), requirements).isEmpty());
        assertTrue(sources.forInput(enterprise, initiator.id(), "another-task", requirements).isEmpty());
        finish(third, 3);

        var finalText = receive("final-text", onlyTask().getShortCode() + " 再问我一个问题");
        routing.route(enterprise, finalText.getId());
        var fourth = startNext();
        transaction.executeWithoutResult(status -> attachments.bind(enterprise, fourth.run().inputMessageId(), List.of(firstFile, secondFile), Instant.now()));
        assertEquals(List.of(1, 0, 1, 0), queries.snapshot(actor, fourth.run().conversationId()).messages().stream()
            .filter(message -> "user".equals(message.role())).map(message -> message.attachments().size()).toList());
        assertFalse(imageInputs.prepare(fourth.run(), List.of(agentInputs.message(fourth.run()))).getFirst().getContent().stream()
            .anyMatch(ImageBlock.class::isInstance));
        finish(fourth, 4);
    }

    private String picture(String id, String reply) throws Exception {
        var frame = message(id, id, "user", "");
        var content = frame.withObject("/event/message");
        content.put("message_type", "image").put("content", json.writeValueAsString(Map.of("image_key", "file-key-" + id)));
        if (reply != null) content.put("parent_id", reply);
        ingress.accept(enterprise, bot.getId(), 1L, frame);
        normalizeAll();
        var incoming = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getPlatformMessageId, id));
        routing.route(enterprise, incoming.getId());
        if (reply == null) PLATFORM.reply("feishu.token");
        PLATFORM.binary(200, Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0U8AAAAASUVORK5CYII="), Map.of("Content-Type", "image/png"));
        assertTrue(downloader.runOnce());
        var resource = databaseAccess.mapper(ChatMessageResourceMapper.class).selectOne(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).eq(ChatMessageResourceRow::getMessageId, incoming.getId()));
        assertTrue(inspections.runNext());
        resources.inspected(enterprise, resource.getId());
        routing.route(enterprise, incoming.getId());
        return resource.getFileId();
    }

    private void finish(Active active, int revision) {
        var result = invoke(active, "complete-" + revision, "collab_complete_task", Map.of("expectedInputRevision", revision,
            "summary", "本轮处理完成。", "artifactRefs", List.of(), "acceptedPriorResults", List.of()));
        assertFalse(result.path("isError").asBoolean(), result::toString);
        lifecycle.finish(active.lease(), "completed", null, null);
        observer.observe(enterprise, link(active.run().id()).getId());
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("测试平台无法启动", failure);
        }
    }
}
