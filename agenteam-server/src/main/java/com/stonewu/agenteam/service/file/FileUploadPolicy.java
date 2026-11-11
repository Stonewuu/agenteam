package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件用途决定大小和允许格式，客户端提交的媒体类型不能扩大允许范围。
 */
@Component
public class FileUploadPolicy {
    private static final Map<String, String> DOCUMENT_TYPES = Map.of("pdf", "application/pdf", "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "txt", "text/plain", "md",
        "text/markdown");
    private static final Map<String, String> OFFICE_TYPES = Map.of(
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    public static final Set<String> UPLOAD_PURPOSES = Set.of("skill_import", "knowledge", "data_import", "attachment");

    public String prepare(FilePrepareRequest request) {
        long limit = maxBytes(request.purpose());
        if (request.sizeBytes() < 1 || request.sizeBytes() > limit) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "FILE_TOO_LARGE", Messages.text("error.file.fileUploadPolicy.prepare.1", (limit / (1024 * 1024))));
        }
        String name = request.name();
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255 || name.contains(
            "/") || name.contains("\\")
            || name.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("name", Messages.text("error.file.fileUploadPolicy.prepare.2"));
        }
        if (request.sha256() == null || !request.sha256().matches("[a-f0-9]{64}")) {
            throw ApiException.invalidField("sha256", Messages.text("error.file.fileUploadPolicy.prepare.3"));
        }
        boolean resourceRequired = Set.of("knowledge", "data_import").contains(request.purpose());
        if (resourceRequired != (request.resourceId() != null)) {
            throw ApiException.invalidField("resourceId",
                resourceRequired ? Messages.text("error.file.fileUploadPolicy.prepare.4") : Messages.text("error.file.fileUploadPolicy.prepare.5"));
        }
        return mediaType(request.purpose(), name);
    }

    public static long maxBytes(String purpose) {
        return switch (purpose) {
            case "skill_import" -> 1024 * 1024;
            case "data_import" -> 10 * 1024 * 1024;
            case "knowledge", "attachment" -> 20 * 1024 * 1024;
            default -> throw ApiException.invalidField("purpose", Messages.text("error.file.fileUploadPolicy.maxBytes.1"));
        };
    }

    public static String mediaType(String purpose, String name) {
        String extension = extension(name);
        String media = switch (purpose) {
            case "skill_import" ->
                Map.of("json", "application/json", "txt", "text/plain", "md", "text/markdown").get(extension);
            case "data_import" -> extension.equals("csv") ? "text/csv" : null;
            case "knowledge" -> DOCUMENT_TYPES.get(extension);
            case "attachment" ->
                ImageFileContent.mediaType(name) != null ? ImageFileContent.mediaType(name)
                    : DOCUMENT_TYPES.containsKey(extension) ? DOCUMENT_TYPES.get(extension) : OFFICE_TYPES.get(extension);
            default -> null;
        };
        if (media == null) {
            throw ApiException.invalidField("name", switch (purpose) {
                case "skill_import" -> Messages.text("error.file.fileUploadPolicy.mediaType.1");
                case "data_import" -> Messages.text("error.file.fileUploadPolicy.mediaType.2");
                case "attachment" -> Messages.text("error.file.fileUploadPolicy.mediaType.3");
                default -> Messages.text("error.file.fileUploadPolicy.mediaType.4");
            });
        }
        return media;
    }

    public static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
