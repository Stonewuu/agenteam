package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 图片只从已授权工作区读取，大小和像素范围在交给模型前检查。
 */
@Component
public class WorkspaceImageReader {
    public record Image(String mediaType, String data, int width, int height) {
        public ImageBlock block() {
            return ImageBlock.builder().source(new Base64Source(mediaType, data)).build();
        }
    }

    public Image read(WorkspaceFilesystem files, String path) {
        try {
            var source = files.resolve(path, false);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > 4L * 1024 * 1024) {
                throw invalid(Messages.text("error.workspace.workspaceImageReader.read.1"));
            }
            byte[] bytes;
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            }
            if (bytes.length > 4 * 1024 * 1024) {
                throw invalid(Messages.text("error.workspace.workspaceImageReader.read.2"));
            }
            try (var image = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(image);
                if (!readers.hasNext()) {
                    throw invalid(Messages.text("error.workspace.workspaceImageReader.read.3"));
                }
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg", "jpg").contains(format)) {
                        throw invalid(Messages.text("error.workspace.workspaceImageReader.read.4"));
                    }
                    reader.setInput(image, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 16000000L) {
                        throw invalid(Messages.text("error.workspace.workspaceImageReader.read.5"));
                    }
                    return new Image(format.equals("png") ? "image/png" : "image/jpeg",
                        Base64.getEncoder().encodeToString(bytes), width, height);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKSPACE_IMAGE_INVALID",
                Messages.text("error.workspace.workspaceImageReader.read.6"), failure);
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKSPACE_IMAGE_INVALID", message);
    }
}
