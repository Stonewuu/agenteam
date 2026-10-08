package com.stonewu.agenteam.mapper.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.sandbox.request.SandboxExecutionRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.sandbox.SandboxExecutionService;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SandboxArchiveMapperTest {
    @TempDir
    Path directory;

    private WorkspaceSettings settings() {
        return new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 16, 1, 30, 256, 1, 32, 30, "none");
    }

    @Test
    void rejectsTraversalAndLargeExpandedFilesBeforePublishingThem() throws Exception {
        var mapper = new SandboxArchiveMapper(new ObjectMapper(), settings());
        try (var control = new ToolCallControl(() -> {
        })) {
            byte[] traversal = archive("work/../../outside.txt", "不允许越过目录".getBytes(StandardCharsets.UTF_8));
            assertThrows(ApiException.class, () -> mapper.read(new ByteArrayInputStream(traversal), directory.resolve("traversal"),
                Set.of("work"), SandboxExecutionRequest.class, control));
            byte[] large = archive("work/large.txt", new byte[2 * 1024 * 1024]);
            assertEquals("WORKSPACE_CAPACITY_EXCEEDED", assertThrows(ApiException.class, () -> mapper.read(new ByteArrayInputStream(large),
                directory.resolve("large"), Set.of("work"), SandboxExecutionRequest.class, control)).code());
        }
    }

    @Test
    void networkMismatchCannotRunWithBroaderServerAccess() throws Exception {
        Path source = directory.resolve("source");
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(source.resolve(area));
        }
        var executor = mock(SandboxExecutor.class);
        var bytes = new ByteArrayOutputStream();
        try (var control = new ToolCallControl(() -> {
        })) {
            new SandboxArchiveMapper(new ObjectMapper(), settings()).write(bytes,
                new SandboxExecutionRequest("test", "echo test", "work", 1000, "bridge"), source,
                Set.of("inputs", "tool-results", "work", "outputs"), control);
        }
        try (var service = new SandboxExecutionService(executor, settings(), new ObjectMapper(), directory.resolve("server"), 2)) {
            assertEquals("SANDBOX_NETWORK_MISMATCH", assertThrows(ApiException.class,
                () -> service.execute(UUID.randomUUID().toString(), new ByteArrayInputStream(bytes.toByteArray()))).code());
            verifyNoInteractions(executor);
        }
    }

    private byte[] archive(String name, byte[] contents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
