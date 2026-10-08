package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceRetentionServiceTest {
    @TempDir
    Path directory;
    private WorkspaceStore store;
    private WorkspaceRetentionService retention;
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final RunRecord run = mock(RunRecord.class);
    private final ConversationRecord conversation = mock(ConversationRecord.class);
    private final ToolCallControl control = new ToolCallControl(() -> {
    });

    @BeforeEach
    void setup() throws Exception {
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("conversation");
        when(run.id()).thenReturn("run");
        when(run.leaseVersion()).thenReturn(1L);
        when(conversation.mode()).thenReturn("normal");
        when(conversation.status()).thenReturn("active");
        when(conversations.find("enterprise", "user", "conversation", false)).thenReturn(Optional.of(conversation));
        var settings = new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 16, 1, 20, 256, 1, 32, 30, "none");
        store = new WorkspaceStore(settings, new ObjectMapper());
        retention = new WorkspaceRetentionService(store, conversations, mock(DockerWorkspaceCommands.class), new ObjectMapper(),
            Clock.fixed(Instant.now().plus(Duration.ofDays(40)), ZoneOffset.UTC), true, 30);
        try (var scope = open()) {
            scope.prepare().write(RuntimeContext.empty(), "work/report.txt", "保留的工作文件");
            scope.commit(Set.of(), Set.of());
        }
    }

    private WorkspaceStore.Scope open() {
        return store.open(run, "conversation", control, Duration.ofSeconds(2), () -> {
        });
    }

    @Test
    void expiresIdleFilesAndKeepsAClearRecordForTheNextRead() throws Exception {
        assertEquals(1, retention.clean());
        try (var scope = open()) {
            assertTrue(scope.cleared());
            assertFalse(scope.current().exists(RuntimeContext.empty(), "work/report.txt"));
        }
    }

    @Test
    void activeExecutionAndHeldFileLockBothPreventDeletion() throws Exception {
        when(conversation.activeRunId()).thenReturn("active-run");
        assertEquals(0, retention.clean());
        when(conversation.activeRunId()).thenReturn(null);
        try (var scope = open()) {
            assertEquals(0, retention.clean());
            assertTrue(scope.current().exists(RuntimeContext.empty(), "work/report.txt"));
        }
    }

    @Test
    void restoresFilesIfANewExecutionStartsWhilePreparingCleanup() throws Exception {
        var active = mock(ConversationRecord.class);
        when(active.activeRunId()).thenReturn("new-run");
        when(conversations.find("enterprise", "user", "conversation", false)).thenReturn(Optional.of(conversation), Optional.of(active));
        assertEquals(0, retention.clean());
        try (var scope = open()) {
            assertEquals("保留的工作文件", scope.current().document("work/report.txt").read(1, null, null, 20, 1024).content());
        }
    }

    @Test
    void databaseFailureNeverMakesExistingFilesLookAbandoned() {
        when(conversations.find("enterprise", "user", "conversation", false)).thenThrow(new IllegalStateException("测试中的数据库不可用"));
        assertEquals(0, retention.clean());
        assertTrue(Files.exists(store.directory(run, "conversation").resolve("current.json")));
    }

    @Test
    void removesAbandonedVersionsWithoutTouchingThePublishedFiles() throws Exception {
        Path abandoned = store.directory(run, "conversation").resolve("versions").resolve(UUID.randomUUID().toString());
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("partial.txt"), "未提交内容");
        Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        var cleanup = new WorkspaceRetentionService(store, conversations, mock(DockerWorkspaceCommands.class), new ObjectMapper(), Clock.systemUTC(), true, 30);
        assertEquals(0, cleanup.clean());
        assertFalse(Files.exists(abandoned));
        try (var scope = open()) {
            assertEquals("保留的工作文件", scope.current().document("work/report.txt").read(1, null, null, 20, 1024).content());
        }
    }
}
