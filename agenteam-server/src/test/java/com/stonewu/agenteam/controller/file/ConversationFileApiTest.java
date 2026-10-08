package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.ConversationWorkspaceStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SharedEnterpriseTestEdition.class)
class ConversationFileApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private RunMapper runs;
    @Autowired
    private ConversationWorkspaceStore workspaces;
    @Autowired
    private ProjectWorkspaceLayout layout;
    @Autowired
    private FileMapper files;
    @Autowired
    private FileContentStorage storage;
    @Autowired
    private MessageAttachmentMapper attachments;
    @Autowired
    private ExecutionMessageSqlMapper messages;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENVIRONMENT.properties(properties);
        properties.add("execution.workspace-files-root", () -> "target/sidebar-api/workspaces");
        properties.add("files.root", () -> "target/sidebar-api/files");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    private RunRecord conversation() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        return runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow();
    }

    private String path(RunRecord run) {
        return base() + "/conversations/" + run.conversationId() + "/files";
    }

    private String save(RunRecord run, String purpose, String name, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var stored = storage.write(enterprise, new ByteArrayInputStream(bytes), Math.max(bytes.length, 1));
        String id = UUID.randomUUID().toString();
        files.generated(new PreparedGeneratedFile(id, enterprise, admin, null, null, purpose.equals("artifact") ? run.id() : null,
            purpose, name, "text/plain", stored.key(), stored.size(), stored.sha256()), Instant.now());
        return id;
    }

    private void workspace(RunRecord run, String text, Set<String> inputs) throws Exception {
        try (var scope = workspaces.open(run, run.conversationId(), new ToolCallControl(() -> {
        }), Duration.ofSeconds(10), () -> {
        })) {
            var prepared = scope.prepare();
            String file = "work/report.md";
            if (prepared.exists(RuntimeContext.empty(), file)) {
                var before = prepared.document(file).read(1, null, null, 100, 32768).content();
                prepared.edit(RuntimeContext.empty(), file, before, text, false);
            } else {
                prepared.write(RuntimeContext.empty(), file, text);
            }
            Files.write(prepared.resolve("outputs/clip.mp4", true), "0123456789".getBytes(StandardCharsets.UTF_8));
            scope.commit(Set.of(), inputs);
        }
    }

    private JsonNode read(String path) throws Exception {
        return data(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void browsesCommittedFilesReadsUtf8AndSearchesWithVersionChecks() throws Exception {
        var run = conversation();
        String original = "# 报告\n第一行🙂\n查找目标\n最后一行";
        workspace(run, original, Set.of());
        var root = read(path(run));
        assertEquals(4, root.path("items").size());
        var listing = read(path(run) + "?path=work");
        schemas.validate("ConversationFile", listing.path("items").get(0));
        String file = listing.path("items").get(0).path("id").asText();
        assertEquals("markdown", listing.path("items").get(0).path("previewKind").asText());
        assertFalse(listing.toString().contains(layout.root().toString()));
        var first = read(path(run) + "/" + file + "/text?limit=16");
        schemas.validate("ConversationFileText", first);
        var rest = read(path(run) + "/" + file + "/text?offset=" + first.path("endOffset").asInt() + "&revision=" + first.path("revision").asText());
        assertEquals(original, first.path("content").asText() + rest.path("content").asText());
        var search = read(path(run) + "/" + file + "/text/search?query=查找目标");
        schemas.validate("ConversationFileSearch", search);
        assertEquals(3, search.path("matches").get(0).path("line").asInt());
        workspace(run, "# 修改后的报告", Set.of());
        mvc.perform(get(path(run) + "/" + file + "/text").param("revision", first.path("revision").asText()).cookie(cookie))
            .andExpect(status().isConflict());
        assertEquals("# 修改后的报告", read(path(run) + "/" + file + "/text").path("content").asText());
        mvc.perform(get(path(run)).param("path", "../outside").cookie(cookie)).andExpect(status().isBadRequest());
        mvc.perform(get(path(run) + "/" + FilePreviewMapper.workspaceId("../outside") + "/text").cookie(cookie)).andExpect(status().isBadRequest());
        mvc.perform(get(path(run))).andExpect(status().isUnauthorized());
    }

    @Test
    void streamsVideoRangesAndRejectsFilesFromAnotherConversation() throws Exception {
        var run = conversation();
        workspace(run, "当前对话", Set.of());
        String file = FilePreviewMapper.workspaceId("outputs/clip.mp4");
        var pending = mvc.perform(get(path(run) + "/" + file + "/content").header("Range", "bytes=2-5").cookie(cookie)).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isPartialContent()).andReturn().getResponse();
        assertEquals("2345", response.getContentAsString());
        assertEquals("bytes 2-5/10", response.getHeader("Content-Range"));
        assertEquals("video/mp4", response.getContentType());
        var other = conversation();
        mvc.perform(get(path(other) + "/" + file + "/text").cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void contextIncludesEarlierMessagesDeduplicatesAndPaginatesRealRelationships() throws Exception {
        var run = conversation();
        String uploaded = save(run, "attachment", "上传资料.txt", "完整附件内容");
        String generated = save(run, "artifact", "产出结果.txt", "产出文件");
        attachments.bind(enterprise, run.inputMessageId(), List.of(uploaded), Instant.now());
        var earlier = messages.selectById(run.inputMessageId());
        earlier.setId(UUID.randomUUID().toString());
        earlier.setCreatedAt(Instant.now().minusSeconds(86400));
        earlier.setUpdatedAt(earlier.getCreatedAt());
        messages.insert(earlier);
        attachments.bind(enterprise, earlier.getId(), List.of(uploaded), Instant.now());
        var first = read(path(run) + "?scope=context&limit=1");
        assertTrue(first.path("hasMore").asBoolean());
        var second = data(mvc.perform(get(path(run)).param("scope", "context").param("limit", "1")
            .param("cursor", first.path("nextCursor").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertFalse(second.path("hasMore").asBoolean());
        assertEquals(Set.of("f." + uploaded, "f." + generated), Set.of(first.path("items").get(0).path("id").asText(), second.path("items").get(0).path("id").asText()));
        assertEquals("完整附件内容", read(path(run) + "/f." + uploaded + "/text").path("content").asText());
        var other = conversation();
        mvc.perform(get(path(other) + "/f." + uploaded + "/text").cookie(cookie)).andExpect(status().isNotFound());
        files.deleted(files.find(enterprise, uploaded, false).orElseThrow(), Instant.now());
        mvc.perform(get(path(run) + "/f." + uploaded + "/text").cookie(cookie)).andExpect(status().isNotFound());
        assertEquals(1, read(path(run) + "?scope=context").path("items").size());
    }

    @Test
    void deletingASourceRevokesWorkspaceAccessEvenAfterTheTextWasCached() throws Exception {
        var run = conversation();
        String uploaded = save(run, "attachment", "来源.txt", "来源正文");
        attachments.bind(enterprise, run.inputMessageId(), List.of(uploaded), Instant.now());
        workspace(run, "由来源文件整理的内容", Set.of(uploaded));
        String file = FilePreviewMapper.workspaceId("work/report.md");
        assertEquals("由来源文件整理的内容", read(path(run) + "/" + file + "/text").path("content").asText());
        files.deleted(files.find(enterprise, uploaded, false).orElseThrow(), Instant.now());
        mvc.perform(get(path(run) + "/" + file + "/text").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path(run)).cookie(cookie)).andExpect(status().isNotFound());
    }
}
