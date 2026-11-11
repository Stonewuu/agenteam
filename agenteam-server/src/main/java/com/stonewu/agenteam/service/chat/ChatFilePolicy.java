package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.file.FileUploadPolicy;
import com.stonewu.agenteam.service.file.ImageFileContent;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/** 平台附件沿用已有文档格式与大小上限，不根据文件名推断未读取的图片或视频。 */
public final class ChatFilePolicy {
    private ChatFilePolicy() {
    }

    public static String mediaType(String name, String type) {
        if (!"file".equals(type) || name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
            || name.codePoints().anyMatch(Character::isISOControl)) {
            throw unsupported();
        }
        String extension = FileUploadPolicy.extension(name);
        if (ImageFileContent.mediaType(name) != null) {
            return ImageFileContent.mediaType(name);
        }
        return switch (extension) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "txt", "md", "pdf", "docx", "xlsx", "pptx" -> FileUploadPolicy.mediaType("attachment", name);
            default -> throw unsupported();
        };
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.CONFLICT, "CHAT_RESOURCE_UNSUPPORTED", Messages.text("error.chat.chatFilePolicy.unsupported.1"));
    }
}
