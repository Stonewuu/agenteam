package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.support.InProcessDocumentParsing;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.service.workspace.WorkspaceInputs;
import io.agentscope.core.message.ToolUseBlock;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationInputService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 模拟真实 HTTP 下载，再经过原文件扫描解析链路，最后进入岗位执行资料。 */
@Import({ChatResourceTest.Platform.class, InProcessDocumentParsing.class})
class ChatResourceTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    @Autowired private ChatResourceWorker downloader;
    @Autowired private ChatResourceTransactions resources;
    @Autowired private FileInspectionWorker inspections;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private TransactionTemplate transaction;
    @Autowired private FileMapper files;
    @Autowired private FileContentStorage storage;
    @Autowired private CollaborationDispatchService dispatch;
    @Autowired private CollaborationInputService inputs;
    @Autowired private ChatIdentityService identities;
    @Autowired private RunMapper runs;
    @Autowired private ChatCapabilityService capabilities;
    @Autowired private ChatSendWorker sender;
    @Autowired private ExecutionToolCatalog toolCatalog;
    @Autowired private ToolCallTransactions toolTransactions;
    @Autowired private ToolExecutionService toolExecution;
    @Autowired private RunStepMapper runSteps;

    @TestConfiguration
    static class Platform {
        @Bean
        @Primary
        IntegrationEndpoints endpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);

        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @BeforeEach
    void fileFixture() throws Exception {
        PLATFORM.reset();
        configureAgent(config -> config.put("attachmentsEnabled", true));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getPinnedVersionId, version));
        transaction.executeWithoutResult(status -> secrets.store(enterprise, bot.getConnectionId(), "test-only-file-secret", admin, 1, Instant.now()));
    }

    @AfterEach
    void stopRuns() {
        for (var task : databaseAccess.mapper(CollaborationTaskMapper.class).selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).in(CollaborationTaskRow::getStatus, "active", "blocked", "waiting_input", "waiting_approval"))) {
            inputs.cancel(identities.actor(enterprise, initiator.id(), binding.getId()), task.getId(), task.getRevision(), "cleanup:" + task.getId(), "结束测试");
        }
        lifecycle.stopForUser(enterprise, initiator.id());
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).in(ChatOutboundMessageRow::getStatus, "pending", "leased", "retry_wait")
            .set(ChatOutboundMessageRow::getStatus, "cancelled"));
        databaseAccess.mapper(ChatMessageResourceMapper.class).update(new LambdaUpdateWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).in(ChatMessageResourceRow::getStatus, "pending", "downloading")
            .set(ChatMessageResourceRow::getStatus, "failed").set(ChatMessageResourceRow::getErrorCode, "TEST_FINISHED")
            .set(ChatMessageResourceRow::getLeaseOwner, null).set(ChatMessageResourceRow::getLeaseUntil, null));
    }

    @Test
    void fileProbeUploadsAsCurrentBotAndUnknownRetryKeepsExactPayloadWithoutUploadingAgain() throws Exception {
        var result = capabilities.send(enterprise, bot.getId(), new ChatCheckRequest(1L, group.getId(), "file", null), "file-probe-one");
        var rows = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        PLATFORM.reply("feishu.token");
        PLATFORM.raw(200, "{\"code\":0,\"data\":{\"file_key\":\"file_test_identity\"}}", Map.of());
        PLATFORM.disconnect();
        assertTrue(sender.runOnce());
        assertEquals("/open-apis/auth/v3/tenant_access_token/internal", PLATFORM.take().uri().getPath());
        var upload = PLATFORM.take();
        assertEquals("/open-apis/im/v1/files", upload.uri().getPath());
        assertTrue(upload.body().contains("name,attendance,salary"));
        assertTrue(upload.header("content-type").startsWith("multipart/form-data"));
        var first = PLATFORM.take();
        var payload = json.readTree(first.body());
        assertEquals("file", payload.path("msg_type").asText());
        assertEquals("file_test_identity", json.readTree(payload.path("content").asText()).path("file_key").asText());
        var row = rows.selectById(result.get("outboundId"));
        assertEquals("retry_wait", row.getStatus());
        rows.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getId, row.getId())
            .set(ChatOutboundMessageRow::getNextAttemptAt, Instant.now().minusSeconds(1)));
        PLATFORM.raw(200, "{\"code\":0,\"data\":{\"message_id\":\"sent-file\"}}", Map.of());
        assertTrue(sender.runOnce());
        var repeated = PLATFORM.take();
        assertEquals(first.body(), repeated.body());
        assertEquals("/open-apis/im/v1/messages", repeated.uri().getPath());
        assertEquals(0, PLATFORM.remainingRequests());
        assertEquals("sent", rows.selectById(row.getId()).getStatus());
        assertTrue(bots.capability(bots.require(enterprise, bot.getId()), "uploadFile"));
        assertEquals(0, taskCount());
    }

    @Test
    void longChineseMessagesKeepEveryCharacterAndRejectChangedRepeatedRequests() {
        String text = "考勤😀工资\n".repeat(1700);
        outbound.text(enterprise, group.getId(), bot.getId(), null, null, "long-reply", "completed", text, null);
        var parts = databaseAccess.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).orderByAsc(ChatOutboundMessageRow::getPartNo));
        assertTrue(parts.size() > 1);
        var restored = new StringBuilder();
        for (var part : parts) {
            String value = cipher.decrypt(enterprise, "outbound", part.getId(), part.getPayloadEncrypted()).path("content").path("text").asText();
            assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= 10000, "飞书按 Markdown 卡片的正文预算分段");
            restored.append(value);
        }
        assertEquals(text, restored.toString());
        assertThrows(ApiException.class, () -> outbound.text(enterprise, group.getId(), bot.getId(), null, null, "long-reply", "completed", text + "changed", null));
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).set(ChatOutboundMessageRow::getStatus, "cancelled"));
    }

    @Test
    void csvAttachmentIsDownloadedScannedAndPassedToRunWithExactDigest() throws Exception {
        var message = receiveResource("file", "考勤.csv");
        routing.route(enterprise, message.getId());
        assertEquals(0, taskCount());
        String csv = "员工编号,考勤天数\nE001,20.5\nE002,18.75\n";
        PLATFORM.reply("feishu.token");
        PLATFORM.raw(200, csv, Map.of("Content-Type", "application/octet-stream", "Content-Disposition", "attachment; filename=attendance.csv"));
        assertTrue(downloader.runOnce());
        var resource = savedResource();
        var downloaded = files.find(enterprise, resource.getFileId(), false).orElseThrow();
        assertEquals("scanning", downloaded.status());
        assertEquals(initiator.id(), downloaded.ownerUserId());
        assertEquals("collaboration_artifact", downloaded.purpose());
        assertEquals(csv.getBytes(StandardCharsets.UTF_8).length, downloaded.sizeBytes());
        PLATFORM.take();
        var request = PLATFORM.take();
        assertEquals("/open-apis/im/v1/messages/resource-message/resources/file_key", request.uri().getPath());
        assertEquals("file", request.query().get("type"));
        assertTrue(inspections.runNext());
        resources.inspected(enterprise, resource.getId());
        assertEquals("ready", savedResource().getStatus());
        routing.route(enterprise, message.getId());
        assertEquals(1, taskCount());
        var accepted = dispatch.submit(dispatch.claim("resource-dispatch").orElseThrow());
        var run = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals(downloaded.id(), run.executionConfig().at("/inputSourceFiles/0/id").asText());
        assertEquals(downloaded.sha256(), run.executionConfig().at("/inputSourceFiles/0/sha256").asText());
        assertTrue(run.executionConfig().path("sourceContext").asText().contains("E001,20.5"));
        assertTrue(readWorkspaceAttachment(accepted.runId(), downloaded.id(), downloaded.originalName()).contains("E001,20.5"));
        assertNull(files.find(enterprise, downloaded.id(), false).orElseThrow().expiresAt());
        try (var content = storage.open(downloaded)) {
            assertEquals(csv, new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        routing.route(enterprise, message.getId());
        assertEquals(1, taskCount());
    }

    @Test
    void unsupportedImageDoesNotDownloadOrStartAModel() {
        var message = receiveResource("image", "image.png");
        routing.route(enterprise, message.getId());
        assertTrue(downloader.runOnce());
        assertEquals("unsupported", savedResource().getStatus());
        assertNull(savedResource().getFileId());
        assertEquals(0, PLATFORM.remainingRequests());
        routing.route(enterprise, message.getId());
        assertEquals("failed", messages().selectById(message.getId()).getRoutingStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void replyingToOwnUnmentionedFileDownloadsExactSourceAndStartsOneTask() throws Exception {
        var original = unmentionedFile("ou_user");
        routing.route(enterprise, original.getId());
        assertEquals("ignored", messages().selectById(original.getId()).getRoutingStatus());
        assertFalse(downloader.runOnce());
        var reply = fileReply("reply-own-file", "请统计本人这份考勤附件");
        routing.route(enterprise, reply.getId());
        assertEquals(0, taskCount());
        assertEquals("awaiting_reference", messages().selectById(original.getId()).getRoutingStatus());
        fetchReferencedCsv();
        routing.route(enterprise, reply.getId());
        var task = onlyTask();
        assertEquals(task.getId(), messages().selectById(original.getId()).getTaskId());
        assertEquals(task.getId(), messages().selectById(reply.getId()).getTaskId());
        var accepted = dispatch.submit(dispatch.claim("reply-file-dispatch").orElseThrow());
        var run = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals(savedResource().getFileId(), run.executionConfig().at("/inputSourceFiles/0/id").asText());
        assertTrue(run.executionConfig().path("sourceContext").asText().contains("E001,20"));
        var originalFile = files.find(enterprise, savedResource().getFileId(), false).orElseThrow();
        assertTrue(readWorkspaceAttachment(accepted.runId(), originalFile.id(), originalFile.originalName()).contains("E001,20"));
        routing.route(enterprise, reply.getId());
        assertEquals(1, taskCount());
        assertEquals(1, databaseAccess.mapper(CollaborationArtifactMapper.class).selectCount(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, enterprise).eq(CollaborationArtifactRow::getKind, "file")));
    }

    @Test
    void explicitTaskCodeAddsOwnReferencedFileToExistingTask() throws Exception {
        var incoming = receive("existing-task", "统计本次合成资料");
        routing.route(enterprise, incoming.getId());
        String taskId = onlyTask().getId();
        var original = unmentionedFile("ou_user");
        routing.route(enterprise, original.getId());
        databaseAccess.mapper(ChatMessageResourceMapper.class).update(new LambdaUpdateWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).eq(ChatMessageResourceRow::getMessageId, original.getId())
            .set(ChatMessageResourceRow::getStatus, "failed").set(ChatMessageResourceRow::getErrorCode, "CHANNEL_BINDING_REQUIRED"));
        var reply = fileReply("supplement-file", onlyTask().getShortCode() + "：补充本人上传的考勤文件");
        routing.route(enterprise, reply.getId());
        assertEquals("pending", savedResource().getStatus());
        assertNull(savedResource().getErrorCode());
        fetchReferencedCsv();
        routing.route(enterprise, reply.getId());
        assertEquals(1, taskCount());
        assertEquals(2, onlyTask().getInputRevision());
        assertEquals(taskId, messages().selectById(original.getId()).getTaskId());
        var accepted = dispatch.submit(dispatch.claim("supplement-file-dispatch").orElseThrow());
        assertEquals(savedResource().getFileId(), runs.find(enterprise, accepted.runId(), false).orElseThrow()
            .executionConfig().at("/inputSourceFiles/0/id").asText());
    }

    @Test
    void replyingToAnotherMembersFileNeverAuthorizesDownload() {
        var original = unmentionedFile("ou_other_user");
        routing.route(enterprise, original.getId());
        var reply = fileReply("other-members-file", "请处理这条附件");
        assertEquals("CHAT_ATTACHMENT_REFERENCE_DENIED", assertThrows(ApiException.class,
            () -> routing.route(enterprise, reply.getId())).code());
        assertNull(messages().selectById(original.getId()).getSenderUserId());
        assertFalse(downloader.runOnce());
        assertEquals(0, taskCount());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    private ChatMessageRow unmentionedFile(String sender) {
        var event = message("standalone-file-event", "standalone-file", "user", "");
        event.withObject("/event/sender/sender_id").put("open_id", sender);
        var body = (ObjectNode) event.at("/event/message");
        body.put("message_type", "file").put("content", json.valueToTree(Map.of("file_key", "file_key", "file_name", "合成考勤.csv")).toString());
        body.putArray("mentions");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getPlatformMessageId, "standalone-file"));
    }

    private ChatMessageRow fileReply(String id, String text) {
        var event = message(id, id, "user", text);
        event.withObject("/event/message").put("parent_id", "standalone-file");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getPlatformMessageId, id));
    }

    private void fetchReferencedCsv() throws Exception {
        PLATFORM.reply("feishu.token");
        PLATFORM.raw(200, "员工编号,考勤天数\nE001,20\n", Map.of("Content-Type", "application/octet-stream"));
        databaseAccess.mapper(ChatMessageResourceMapper.class).update(new LambdaUpdateWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).set(ChatMessageResourceRow::getNextAttemptAt, Instant.now().minusSeconds(1)));
        assertTrue(downloader.runOnce());
        PLATFORM.take();
        assertEquals("/open-apis/im/v1/messages/standalone-file/resources/file_key", PLATFORM.take().uri().getPath());
        assertTrue(inspections.runNext());
        resources.inspected(enterprise, savedResource().getId());
        assertEquals("ready", savedResource().getStatus());
    }

    private String readWorkspaceAttachment(String runId, String fileId, String name) {
        var lease = lifecycle.claim("actual-file-tool").orElseThrow();
        assertEquals(runId, lease.runId());
        var run = lifecycle.start(lease).orElseThrow();
        var tool = toolCatalog.list(run).values().stream().filter(value -> value.definition().name().equals("read_file")).findFirst().orElseThrow();
        var use = ToolUseBlock.builder().id("read-original-csv").name(tool.alias())
            .input(Map.of("path", WorkspaceInputs.inputPath(fileId, name))).build();
        String step = ExecutionStepIds.id(run, "tool-step:" + run.conversationId() + ":read-original-csv");
        runSteps.save(run, new RunStepView(step, null, runSteps.attemptId(run), "tool", "read_file", 0,
            "running", null, Instant.now().toString(), null, null), null, Instant.now());
        toolTransactions.prepare(lease, tool, run.conversationId(), null, use);
        var result = toolExecution.invoke(run, lease, tool, run.conversationId(), use, Duration.ofSeconds(30),
            new AtomicReference<ToolCallControl>(), () -> false);
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return result.toString();
    }

    @Test
    void failureToFetchResourceNeverStartsAPartialTask() {
        var message = receiveResource("file", "考勤.csv");
        routing.route(enterprise, message.getId());
        PLATFORM.reply("feishu.token");
        PLATFORM.raw(403, "{\"code\":99991672,\"msg\":\"resource permission denied\"}", Map.of("X-Tt-Logid", "resource-test-trace"));
        assertTrue(downloader.runOnce());
        assertNull(savedResource().getFileId());
        assertNotEquals("ready", savedResource().getStatus());
        routing.route(enterprise, message.getId());
        assertEquals(0, taskCount());
    }

    private ChatMessageRow receiveResource(String type, String name) {
        var event = message("resource-event", "resource-message", "user", "");
        String field = type.equals("image") ? "image_key" : "file_key";
        ((ObjectNode) event.at("/event/message")).put("message_type", type)
            .put("content", json.valueToTree(Map.of(field, "file_key", "file_name", name)).toString());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        return onlyMessage();
    }

    private ChatMessageResourceRow savedResource() {
        return databaseAccess.mapper(ChatMessageResourceMapper.class).selectOne(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, enterprise));
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("测试平台无法启动", failure);
        }
    }
}
