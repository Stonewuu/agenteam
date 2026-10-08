package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceArchiveTest {
    @TempDir
    Path root;

    @Test
    void rejectsLinksAndTraversalWithoutWritingOutsideTheNewDirectory() throws Exception {
        var settings = new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 20, 256, 1, 32, 15, "none");
        for (byte kind : new byte[]{TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO}) {
            var entry = new TarArchiveEntry("work/link", kind);
            entry.setLinkName("../../outside");
            try (var control = new ToolCallControl(() -> {
            })) {
                assertThrows(ApiException.class, () -> WorkspaceArchive.extract(new ByteArrayInputStream(archive(entry)), root.resolve("output"), "work", settings, control));
            }
        }
        var traversal = new TarArchiveEntry("work/../../outside");
        traversal.setSize(0);
        try (var control = new ToolCallControl(() -> {
        })) {
            assertThrows(ApiException.class, () -> WorkspaceArchive.extract(new ByteArrayInputStream(archive(traversal)), root.resolve("output"), "work", settings, control));
        }
        assertFalse(Files.exists(root.resolve("outside")));
        var absolute = new TarArchiveEntry("/workspace/work/absolute.txt", true);
        absolute.setSize(0);
        try (var control = new ToolCallControl(() -> {
        })) {
            assertThrows(ApiException.class, () -> WorkspaceArchive.extract(new ByteArrayInputStream(archive(absolute)), root.resolve("output"), "work", settings, control));
        }
    }

    private byte[] archive(TarArchiveEntry entry) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new TarArchiveOutputStream(bytes)) {
            output.putArchiveEntry(entry);
            output.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
