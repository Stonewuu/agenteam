package com.stonewu.agenteam.service.file;

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
                "FILE_TOO_LARGE", "此类文件不能超过 " + (limit / (1024 * 1024)) + " MiB。");
        }
        String name = request.name();
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255 || name.contains(
            "/") || name.contains("\\")
            || name.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("name", "请使用不含路径或控制字符的文件名。");
        }
        if (request.sha256() == null || !request.sha256().matches("[a-f0-9]{64}")) {
            throw ApiException.invalidField("sha256", "文件摘要格式不正确，请重新选择文件。");
        }
        boolean resourceRequired = Set.of("knowledge", "data_import").contains(request.purpose());
        if (resourceRequired != (request.resourceId() != null)) {
            throw ApiException.invalidField("resourceId",
                resourceRequired ? "请先选择所属资料资源。" : "此类文件不直接关联资料资源。");
        }
        return mediaType(request.purpose(), name);
    }

    public static long maxBytes(String purpose) {
        return switch (purpose) {
            case "skill_import" -> 1024 * 1024;
            case "data_import" -> 10 * 1024 * 1024;
            case "knowledge", "attachment" -> 20 * 1024 * 1024;
            default -> throw ApiException.invalidField("purpose", "请选择支持的文件用途。");
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
                DOCUMENT_TYPES.containsKey(extension) ? DOCUMENT_TYPES.get(extension) : OFFICE_TYPES.get(extension);
            default -> null;
        };
        if (media == null) {
            throw ApiException.invalidField("name", switch (purpose) {
                case "skill_import" -> "请选择 JSON、Markdown 或 TXT 技能文件。";
                case "data_import" -> "请选择 UTF-8 编码的 CSV 文件。";
                case "attachment" -> "请选择 PDF、Word、Excel、PowerPoint 或文本文件。";
                default -> "请选择有文字内容的 PDF、DOCX、TXT 或 Markdown 文件。";
            });
        }
        return media;
    }

    public static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
