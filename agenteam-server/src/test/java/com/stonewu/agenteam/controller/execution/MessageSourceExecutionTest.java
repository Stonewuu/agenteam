package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.file.FileRetentionService;
import com.stonewu.agenteam.service.knowledge.KnowledgeProcessingWorker;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实附件与知识资料进入既有模型对话流，并验证提交、历史和清理关系。
 */
@Import(SharedEnterpriseTestEdition.class)
class MessageSourceExecutionTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final FileScanTestServer SCANNER = new FileScanTestServer();

    @Autowired
    private FileInspectionWorker inspector;

    @Autowired
    private FileMapper files;

    @Autowired
    private KnowledgeProcessingWorker knowledge;

    @Autowired
    private RunMapper runs;

    @Autowired
    private PreviewRetentionService previews;

    @Autowired
    private FileRetentionService retention;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", () -> "target/p05-message-source-files");
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
        registry.add("knowledge.processing.enabled", () -> false);
        registry.add("knowledge.retention.enabled", () -> false);
        registry.add("execution.workspace-root", () -> "target/p05-message-source-workspace");
        registry.add("execution.state-root", () -> "target/p05-message-source-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        SCANNER.close();
        ENVIRONMENT.close();
    }

    @Test
    void attachmentOnlyMessagesKeepTheirFilesAndReachTheModelExactlyOnce() throws Exception {
        configureAgent(config -> config.put("attachmentsEnabled", true));
        String text = "本次附件中的橙石活动在星期五举行，请按资料准备。", file = upload("attachment", null, "活动.txt", text.getBytes(StandardCharsets.UTF_8), true);
        var input = new LinkedHashMap<>(input(""));
        input.put("attachmentIds", List.of(file));
        String key = UUID.randomUUID().toString();
        var body = Map.of("agentId", agent, "input", input);
        var accepted = data(write(base() + "/conversations", body, key).andExpect(status().isAccepted()).andReturn());
        assertEquals(accepted, data(write(base() + "/conversations", body, key).andExpect(status().isAccepted()).andReturn()));
        assertEquals(1, count("message_attachment"));
        assertNull(files.find(enterprise, file, false).orElseThrow().expiresAt());
        assertTrue(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ConversationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getTitle).eq(AgentConversationRow::getId, (accepted.path("conversationId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getTitle()).toList()).startsWith("资料处理 "));
        var modelInput = new AtomicReference<String>();
        modelResponse = exchange -> {
            try {
                modelInput.set(json.readTree(exchange.getRequestBody()).toString());
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var response = Map.of("id", "attachment-response", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "已读取附件中的活动安排。"), "finish_reason", "stop")));
                exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        };
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().terminal());
            assertEquals("completed", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status());
            assertTrue(modelInput.get().contains(text));
            assertEquals(callsBefore + 1, modelCalls.get());
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals(file, snapshot.at("/messages/0/attachments/0/id").asText());
            assertEquals("活动.txt", snapshot.at("/messages/0/attachments/0/name").asText());
            assertEquals("", snapshot.at("/messages/0/content").asText());
        } finally {
            worker.close();
        }
    }

    @Test
    void pdfWithoutTextCanBeSubmittedAndKeepsTheOriginalFileReference() throws Exception {
        configureAgent(config -> config.put("attachmentsEnabled", true));
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            bytes = output.toByteArray();
        }
        String file = upload("attachment", null, "无文字资料.pdf", bytes, true);
        var input = new LinkedHashMap<>(input(""));
        input.put("attachmentIds", List.of(file));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var run = runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow();
        assertEquals(file, run.executionConfig().at("/inputSourceFiles/0/id").asText());
        assertTrue(run.executionConfig().path("sourceContext").asText().contains("未提取到文字"));
        assertTrue(run.executionConfig().path("sourceContext").asText().contains("不能根据文件名推测或编造内容"));
        assertNull(files.find(enterprise, file, false).orElseThrow().expiresAt());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertEquals(file, snapshot.at("/messages/0/attachments/0/id").asText());
        assertEquals("FILE_NO_TEXT", snapshot.at("/messages/0/attachments/0/errorCode").asText());
    }

    @Test
    void unpreparedWrongPurposeAndDisabledAttachmentsNeverCreateMessagesOrReserveQuota() throws Exception {
        String ready = upload("attachment", null, "附件.txt", "已准备附件".getBytes(StandardCharsets.UTF_8), true);
        var input = new LinkedHashMap<>(input("整理附件"));
        input.put("attachmentIds", List.of(ready));
        write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isConflict());
        configureAgent(config -> config.put("attachmentsEnabled", true));
        String pending = upload("attachment", null, "未准备.txt", "未准备附件".getBytes(StandardCharsets.UTF_8), false);
        input.put("attachmentIds", List.of(pending));
        write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isConflict());
        String skill = upload("skill_import", null, "技能.txt", "技能不能冒充附件".getBytes(StandardCharsets.UTF_8), true);
        input.put("attachmentIds", List.of(skill));
        write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("agent_message"));
        assertEquals(0, count("message_attachment"));
        assertEquals(0, reserved());
    }

    @Test
    void allAttachmentBytesAreCountedBeforeAnythingIsSubmitted() throws Exception {
        configureAgent(config -> config.put("attachmentsEnabled", true));
        byte[] text = new byte[17 * 1024 * 1024];
        Arrays.fill(text, (byte) 'a');
        String a = upload("attachment", null, "一.txt", text, true), b = upload("attachment", null, "二.txt", text, true), c = upload("attachment", null, "三.txt", text, true);
        var input = new LinkedHashMap<>(input("整理资料"));
        input.put("attachmentIds", List.of(a, b, c));
        write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("message_attachment"));
        assertEquals(0, reserved());
    }

    @Test
    void selectedKnowledgeUsesServerExcerptsAndDeletionPreventsAQueuedModelCall() throws Exception {
        var config = Map.of("icon", "BookOpen", "color", "blue", "description", "", "retrievalMode", "keyword", "maxResults", 8, "maxContextCharacters", 8000);
        String resource = data(write(base() + "/resources", Map.of("kind", "knowledge", "name", "所选资料", "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String original = "引用中的橙石项目在九月十五日交付。", file = upload("knowledge", resource, "交付资料.txt", original.getBytes(StandardCharsets.UTF_8), true);
        String document = data(write(base() + "/knowledge/" + resource + "/documents", Map.of("fileIds", List.of(file)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).get(0).path("id").asText();
        assertTrue(knowledge.runNext());
        var input = new LinkedHashMap<>(input("按所选资料回答"));
        input.put("knowledgeReferences", List.of(Map.of("documentId", document, "generation", 1)));
        write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isConflict());
        String knowledgeRevision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (resource))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        String knowledgeVersion = data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", Map.of("releaseNote", "发布输入资料"), knowledgeRevision, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(agentConfig -> agentConfig.set("knowledgeVersionIds", json.valueToTree(List.of(knowledgeVersion))));
        var options = data(mvc.perform(get(base() + "/agents/" + agent + "/input-options?kind=document").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(document, options.at("/items/0/documentId").asText());
        assertEquals(1, options.at("/items/0/generation").asInt());
        schemas.validate("InputOption", options.path("items").get(0));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertEquals(original, snapshot.at("/messages/0/blocks/0/citation/excerpt").asText());
        configureAgent(agentConfig -> agentConfig.putArray("knowledgeVersionIds"));
        assertEquals(0, data(mvc.perform(get(base() + "/agents/" + agent + "/input-options?kind=document").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").size());
        assertEquals(document, data(mvc.perform(get(base() + "/agents/" + agent + "/input-options?kind=document&conversationId=" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn()).at("/items/0/documentId").asText());
        assertEquals(file, snapshot.at("/messages/0/blocks/0/citation/fileId").asText());
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(KnowledgeDocumentSqlMapper.class).selectList(new LambdaQueryWrapper<KnowledgeDocumentRow>().select(KnowledgeDocumentRow::getRevision).eq(KnowledgeDocumentRow::getId, (document))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.DELETE, base() + "/knowledge/" + resource + "/documents/" + document, null, revision, UUID.randomUUID().toString()).andExpect(status().isOk());
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().terminal());
        } finally {
            worker.close();
        }
        assertEquals("failed", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status());
    }

    @Test
    void expiredPreviewsRemoveAttachmentRelationsAndFilesAndImmediatelyDenyDownloads() throws Exception {
        configureAgent(config -> config.put("attachmentsEnabled", true));
        String file = upload("attachment", null, "预览附件.txt", "预览中的输入资料".getBytes(StandardCharsets.UTF_8), true);
        var input = new LinkedHashMap<>(input("预览附件"));
        input.put("attachmentIds", List.of(file));
        var accepted = data(preview(Map.of("input", input), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var download = data(mvc.perform(get(base() + "/files/" + file + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
        lifecycle.stopForUser(enterprise, admin);
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (accepted.path("conversationId").asText())).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        mvc.perform(get(URI.create(download.path("url").asText())).cookie(cookie)).andExpect(status().isNotFound());
        previews.clean();
        retention.clean();
        assertEquals(0, count("message_attachment"));
        assertEquals(0, count("agent_message"));
        assertTrue(files.find(enterprise, file, false).isEmpty());
    }

    private String upload(String purpose, String resource, String name, byte[] bytes, boolean scan) throws Exception {
        var input = new LinkedHashMap<String, Object>();
        input.put("purpose", purpose);
        input.put("resourceId", resource);
        input.put("name", name);
        input.put("mediaType", "text/plain");
        input.put("sizeBytes", bytes.length);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        input.put("sha256", hash);
        String file = data(write(base() + "/files", input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + file + "/content").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType(MediaType.TEXT_PLAIN).content(bytes)).andExpect(status().isNoContent());
        if (scan) {
            write(base() + "/files/" + file + "/complete", Map.of("sizeBytes", bytes.length, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isAccepted());
            assertTrue(inspector.runNext());
            assertEquals("ready", files.find(enterprise, file, false).orElseThrow().status(), () -> files.find(enterprise, file, false).orElseThrow().errorCode());
        }
        return file;
    }

    @Test
    void workflowReturnsTheActualUploadedAttachmentWithoutRewritingItsIdentityOrSize() throws Exception {
        byte[] bytes = "本次工作流处理的实际附件。".getBytes(StandardCharsets.UTF_8);
        String file = upload("attachment", null, "流程附件.txt", bytes, true);
        var nodes = List.of(Map.of("nodeId", "start", "type", "start", "name", "开始", "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", Map.of("inputSchema", Map.of("type", "object"))), Map.of("nodeId", "end", "type", "end", "name", "结束", "position", Map.of("x", 0, "y", 160), "timeoutSeconds", 30, "failurePolicy", "stop", "config", Map.of("outputMapping", Map.of("text", "处理完成", "attachments", "${input.attachments}"))));
        var graph = Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", List.of(Map.of("edgeId", "finish", "source", "start", "target", "end", "branch", "default")));
        var resource = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "附件流程", "description", "", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        var published = data(change(HttpMethod.POST, base() + "/resources/" + resource.at("/resource/id").asText() + "/publish", Map.of("releaseNote", "验证真实附件"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String flowVersion = published.at("/version/id").asText();
        configureAgent(config -> {
            config.put("attachmentsEnabled", true).put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", flowVersion);
            config.remove("temperature");
            config.withArray("workflowVersionIds").add(flowVersion);
        });
        var input = new LinkedHashMap<>(input("整理资料"));
        input.put("attachmentIds", List.of(file));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertEquals("处理完成", snapshot.at("/messages/1/content").asText());
        var result = snapshot.at("/messages/1/blocks").findValues("file").stream().filter(value -> value.path("id").asText().equals(file)).findFirst().orElseThrow();
        assertEquals(bytes.length, result.path("sizeBytes").asInt());
        assertEquals(snapshot.at("/messages/0/attachments/0"), result);
        assertEquals(callsBefore, modelCalls.get());
    }
}
