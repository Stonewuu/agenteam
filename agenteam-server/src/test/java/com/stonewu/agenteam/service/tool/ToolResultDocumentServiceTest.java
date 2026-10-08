package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolResultDocumentServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallMapper calls = mock(ToolCallMapper.class);
    private final RunMapper runs = mock(RunMapper.class);
    private final ConversationQueryService conversations = mock(ConversationQueryService.class);
    private final SourceToolResultAccessService sources = mock(SourceToolResultAccessService.class);
    private final FileAccessService files = mock(FileAccessService.class);
    private final FileContentStorage storage = mock(FileContentStorage.class);
    private final AuthContext actor = mock(AuthContext.class);
    private final ToolCallRecord call = mock(ToolCallRecord.class);
    private final RunRecord run = mock(RunRecord.class);
    private final ToolResultDocumentService documents = new ToolResultDocumentService(calls, runs, conversations, sources, mock(ResourcePolicy.class), files,
        storage, mapper, new ToolResultSettings(32, 64, 16, 2, 8, 200, 2, 20), mock(FileMapper.class), Clock.systemUTC());

    @BeforeEach
    void setup() {
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        when(call.id()).thenReturn("result");
        when(call.enterpriseId()).thenReturn("enterprise");
        when(call.actorUserId()).thenReturn("user");
        when(call.runId()).thenReturn("run");
        when(call.resourceVersionId()).thenReturn("version");
        when(call.frameworkSessionId()).thenReturn("parent");
        when(call.resultRedacted()).thenReturn(mapper.createObjectNode().put("content", "第一行\n最后一行"));
        when(calls.find("enterprise", "result", false)).thenReturn(Optional.of(call));
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("conversation");
        when(runs.find("enterprise", "run", false)).thenReturn(Optional.of(run));
    }

    @Test
    void readsTheStoredResultAndChecksCurrentSourceAccessOnEveryRead() {
        var first = documents.open(actor, "conversation", "tool-results/result/content.txt", "parent", Set.of());
        assertEquals("最后一行", first.text().read(2, 2, null, 200, 8192).content());
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "资料已不可读取。")).when(sources).requireResult(actor, call);
        assertThrows(ApiException.class, () -> documents.open(actor, "conversation", "tool-results/result/content.txt", "parent", Set.of()));
        verify(sources, times(2)).requireResult(actor, call);
    }

    @Test
    void rejectsOtherConversationsAndActorsBeforeOpeningFiles() {
        assertThrows(ApiException.class, () -> documents.open(actor, "other-conversation", "tool-results/result/content.txt", null, Set.of()));
        when(call.actorUserId()).thenReturn("other-user");
        assertThrows(ApiException.class, () -> documents.open(actor, "conversation", "tool-results/result/content.txt", null, Set.of()));
        verifyNoInteractions(storage);
        verifyNoInteractions(sources);
    }

    @Test
    void requiresAnExplicitReferenceBeforeSharingWithAChild() {
        assertThrows(ApiException.class, () -> documents.open(actor, "conversation", "tool-results/result/content.txt", "child", Set.of()));
        var shared = documents.open(actor, "conversation", "tool-results/result/content.txt", "child", Set.of("result"));
        assertEquals(2, shared.text().totalLines());
    }

    @Test
    void cachedFileStillChecksExpiryBeforeEveryRead() throws Exception {
        byte[] bytes = "{\"content\":\"完整结果\"}".getBytes(StandardCharsets.UTF_8);
        when(call.resourceId()).thenReturn("source");
        when(call.resultRedacted()).thenReturn(mapper.createObjectNode().put("truncated", true).put("fileId", "result"));
        var file = mock(FileRecord.class);
        when(file.id()).thenReturn("result");
        when(file.enterpriseId()).thenReturn("enterprise");
        when(file.ownerUserId()).thenReturn("user");
        when(file.runId()).thenReturn("run");
        when(file.resourceId()).thenReturn("source");
        when(file.resourceVersionId()).thenReturn("version");
        when(file.purpose()).thenReturn("artifact");
        when(file.status()).thenReturn("ready");
        when(file.sizeBytes()).thenReturn((long) bytes.length);
        when(file.sha256()).thenReturn(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        when(files.ready(actor, "result")).thenReturn(file);
        when(storage.open(file)).thenReturn(new ByteArrayInputStream(bytes));
        var first = documents.open(actor, "conversation", "tool-results/result/content.txt", "parent", Set.of());
        assertSame(first.text(), documents.open(actor, "conversation", "tool-results/result/content.txt", "parent", Set.of()).text());
        verify(storage, times(1)).open(file);
        when(file.expiresAt()).thenReturn(Instant.EPOCH);
        assertThrows(ApiException.class, () -> documents.open(actor, "conversation", "tool-results/result/content.txt", "parent", Set.of()));
    }

    @Test
    void rejectsHostPathsAndTraversal() {
        for (String path : new String[]{"C:/Windows/system.ini", "../tool-results/result/content.txt", "tool-results/../content.txt", "tool-results/result/../../content.txt"}) {
            assertThrows(ApiException.class, () -> documents.open(actor, "conversation", path, null, Set.of()));
        }
        verifyNoInteractions(calls);
        verifyNoInteractions(storage);
    }
}
