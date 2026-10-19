package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.CommandResultSettings;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.project.entity.ProjectCommandRecord;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.project.entity.SandboxRunUsage;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectPaths;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.support.TestOutputPaths;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CommandResultCacheTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TestClock clock = new TestClock();
    private Path root;
    private WorkspaceSettings workspace;
    private CommandResultCache cache;
    private CommandResultCache.Area area;

    @BeforeEach
    void prepare() throws Exception {
        root = Files.createDirectories(TestOutputPaths.path("command-cache", UUID.randomUUID().toString()));
        workspace = new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 200, 256, 1, 32, 30, "none");
        cache = new CommandResultCache(new CommandResultSettings(7, 4, 100), workspace, json, clock);
        area = new CommandResultCache.Area(root.resolve("cache"), root.resolve("control"), "project");
    }

    @Test
    void completeOutputIsPagedAndSearchedThroughFileToolsWithScopeAndWriteProtection() throws Exception {
        String content = "命令输出🙂\n".repeat(500) + "最后一行：完整结果";
        try (var control = control()) {
            save("long", content, control);
            var reader = new WorkspaceFileReader(new ResourceJson(json), new ToolResultSettings(32, 64, 2, 1, 20, 2, 20));
            var query = json.createObjectNode().put("path", CommandResultCache.output("long", "stdout"));
            int pages = 0;
            String previous = null;
            boolean tail = false;
            while (true) {
                var page = cache.read(area, query.path("path").asText(), control,
                    files -> reader.read(files, "cache", "read_file", query, 4096));
                tail |= page.path("content").asText().contains("最后一行：完整结果");
                pages++;
                assertTrue(pages < 100);
                if (page.path("rangeComplete").asBoolean()) {
                    break;
                }
                String next = page.path("nextCursor").asText();
                assertNotEquals(previous, next);
                previous = next;
                query.put("cursor", next);
            }
            assertTrue(pages > 1);
            assertTrue(tail);
            var search = json.createObjectNode().put("path", query.path("path").asText()).put("pattern", "最后一行");
            assertEquals(1, cache.read(area, search.path("path").asText(), control,
                files -> reader.read(files, "cache", "grep_files", search, 4096)).path("matches").size());
            assertThrows(ApiException.class, () -> cache.read(area, query.path("path").asText(), control,
                files -> files.write(RuntimeContext.empty(), query.path("path").asText(), "不能覆盖")));
            var other = new CommandResultCache.Area(area.data(), area.records(), "other-project");
            assertEquals("COMMAND_RESULT_EXPIRED", assertThrows(ApiException.class,
                () -> cache.read(other, query.path("path").asText(), control, files -> files.bytes(query.path("path").asText()))).code());
            assertThrows(ApiException.class, () -> cache.read(area, "command-results/../../secret", control, files -> null));
        }
    }

    @Test
    void expiryProtectsRunningCommandsAndActiveTasksAndReadingRenewsRetention() throws Exception {
        try (var control = control()) {
            save("done", "完整输出", control);
            cache.prepare(area, "running", Set.of(), control);
            clock.advance(Duration.ofDays(8));
            assertEquals(0, cache.clean(area, Set.of("project"), control));
            assertEquals("完整输出", cache.read(area, CommandResultCache.output("done", "stdout"), control,
                files -> files.document(CommandResultCache.output("done", "stdout")).read(1, null, null, 20, 4096).content()));
            assertEquals(0, cache.clean(area, Set.of(), control));
            clock.advance(Duration.ofDays(8));
            assertEquals(1, cache.clean(area, Set.of(), control));
            assertTrue(cache.contains(area, "running"));
            assertFalse(cache.contains(area, "done"));
            assertThrows(ApiException.class, () -> cache.read(area, CommandResultCache.output("done", "stdout"), control, files -> null));
        }
    }

    @Test
    void capacityReservesBothStreamsAndNeverEvictsActiveOutput() throws Exception {
        try (var control = control()) {
            save("first", "a".repeat(1024 * 1024), control);
            clock.advance(Duration.ofSeconds(1));
            save("second", "b".repeat(1024 * 1024), control);
            cache.prepare(area, "running", Set.of("project"), control);
            assertEquals("COMMAND_RESULT_CAPACITY", assertThrows(ApiException.class,
                () -> cache.prepare(area, "blocked", Set.of("project"), control)).code());
            assertTrue(cache.contains(area, "first"));
            cache.prepare(area, "next", Set.of(), control);
            assertTrue(cache.contains(area, "running"));
            assertFalse(cache.contains(area, "first"));
            assertFalse(cache.contains(area, "second"));
        }
    }

    @Test
    void cleanupInAnotherInstanceWaitsForTheWholeRead() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var control = control(); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            save("reading", "持续读取", control);
            clock.advance(Duration.ofDays(8));
            var read = threads.submit(() -> {
                try (var reading = control()) {
                    return cache.read(area, CommandResultCache.output("reading", "stdout"), reading, files -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                        return files.bytes(CommandResultCache.output("reading", "stdout"));
                    });
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var other = new CommandResultCache(new CommandResultSettings(7, 4, 100), workspace, json, clock);
            assertFalse(other.capacityExceeded(area));
            var cleanup = threads.submit(() -> {
                try (var cleaning = control()) {
                    return other.clean(area, Set.of(), cleaning);
                }
            });
            try {
                assertThrows(TimeoutException.class, () -> cleanup.get(150, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(read.get(5, TimeUnit.SECONDS).length > 0);
            assertEquals(0, cleanup.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedLegacyFilesMoveOnlyAfterParentTaskReleasesTheProject() throws Exception {
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.resolve("users").toString(), ""), json);
        var project = new ProjectLocation(ProjectPaths.workspaceId("enterprise", "user"), "project", "enterprise", "user", "projects/project");
        layout.prepareWorkspace(project.workspaceId(), false);
        Path files = Files.createDirectories(layout.project(project).resolve("work/command-results/legacy"));
        Files.writeString(files.resolve("stdout.txt"), "历史完整输出");
        Files.writeString(files.resolve("stderr.txt"), "");
        Files.writeString(layout.project(project).resolve("business.txt"), "业务文件");
        layout.register(project);
        var registry = new UserSandboxRegistry(layout);
        var old = new SandboxExecutor.Result(0, false, false, "work/command-results/legacy/stdout.txt", "work/command-results/legacy/stderr.txt");
        registry.save(new ProjectCommandRecord(project.projectId(), "hash", old, project, "legacy", "container", "generation", UUID.randomUUID().toString(), 0, clock.millis(), "completed"));
        var usage = new SandboxRunUsage("run", 1, "session", project, "job", "worker", clock.millis());
        registry.save(usage);
        var retention = new CommandResultRetentionService(layout, registry, new WorkspaceStore(workspace, json), mock(ConversationMapper.class), cache);
        retention.cleanProject(project.workspaceId());
        assertTrue(Files.exists(files.resolve("stdout.txt")));
        registry.remove(usage);
        retention.cleanProject(project.workspaceId());
        assertFalse(Files.exists(files));
        assertEquals("历史完整输出", Files.readString(layout.commandResults(project).files().resolve(CommandResultCache.output("legacy", "stdout"))));
        assertEquals("业务文件", Files.readString(layout.project(project).resolve("business.txt")));
    }

    private void save(String call, String content, ToolCallControl control) throws Exception {
        cache.prepare(area, call, Set.of(), control);
        cache.capture(area, call, control, path -> {
            Files.writeString(path.resolve("stdout.txt"), content);
            Files.writeString(path.resolve("stderr.txt"), "");
        });
    }

    private ToolCallControl control() {
        return new ToolCallControl(() -> {
        });
    }

    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T00:00:00Z");
        void advance(Duration duration) {
            now = now.plus(duration);
        }
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
        @Override
        public Instant instant() {
            return now;
        }
    }
}
