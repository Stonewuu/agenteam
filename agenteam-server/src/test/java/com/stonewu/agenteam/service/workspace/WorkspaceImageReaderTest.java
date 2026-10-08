package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceImageReaderTest {
    @TempDir
    Path directory;

    @Test
    void acceptsRealImagesAndRejectsTextOrPathsOutsideWorkspace() throws Exception {
        Files.createDirectories(directory.resolve("outputs"));
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("outputs/page.png").toFile());
        Files.writeString(directory.resolve("outputs/fake.png"), "并非图片");
        var settings = new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 16, 5, 100, 256, 1, 32, 30, "none");
        var files = new WorkspaceFilesystem(directory, settings, () -> {
        }, false);
        var reader = new WorkspaceImageReader();
        var image = reader.read(files, "outputs/page.png");
        assertEquals(30, image.width());
        assertEquals(20, image.height());
        assertEquals("image/png", image.mediaType());
        assertThrows(ApiException.class, () -> reader.read(files, "outputs/fake.png"));
        assertThrows(ApiException.class, () -> reader.read(files, "outputs/../../outside.png"));
    }
}
