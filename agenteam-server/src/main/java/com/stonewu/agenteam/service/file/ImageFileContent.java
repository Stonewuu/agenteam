package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.FileRecord;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/** 图片不做文字解析，原始内容校验后仅在模型支持时读取为图片输入。 */
@Component
public class ImageFileContent {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
        "gif", "image/gif", "webp", "image/webp");
    private final FileContentStorage storage;

    public ImageFileContent(FileContentStorage storage) {
        this.storage = storage;
    }

    public static boolean supports(String media) {
        return media != null && TYPES.containsValue(media);
    }

    public static String mediaType(String name) {
        return name == null ? null : TYPES.get(FileUploadPolicy.extension(name));
    }

    public static String name(String media) {
        return "图片." + switch (media) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        };
    }

    public static String detect(byte[] bytes) {
        if (bytes.length >= 12) {
            String header = new String(bytes, 0, 12, StandardCharsets.ISO_8859_1);
            if (header.startsWith("\u0089PNG\r\n\u001a\n")) return "image/png";
            if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
            if (header.startsWith("GIF87a") || header.startsWith("GIF89a")) return "image/gif";
            if (header.startsWith("RIFF") && header.substring(8).equals("WEBP")) return "image/webp";
        }
        throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
    }

    public String detect(String enterprise, FileContentStorage.Stored stored) {
        try (var input = storage.open(enterprise, stored)) {
            return detect(input.readNBytes(12));
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        }
    }

    public ImageBlock read(FileRecord file) {
        try (var input = storage.open(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES || bytes.length != file.sizeBytes()
                || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256())
                || !detect(bytes).equals(file.mediaType())) {
                throw DocumentParserProcess.failure("FILE_CONTENT_MISMATCH");
            }
            return ImageBlock.builder().source(new Base64Source(file.mediaType(), Base64.getEncoder().encodeToString(bytes))).build();
        } catch (IOException failure) {
            throw FileStorageKeys.storageUnavailable(failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", failure);
        }
    }
}
