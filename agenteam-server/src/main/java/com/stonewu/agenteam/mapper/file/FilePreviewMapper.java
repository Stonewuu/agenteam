package com.stonewu.agenteam.mapper.file;

import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只为浏览器可直接显示的格式提供内嵌预览，其他文件保留下载。
 */
public final class FilePreviewMapper {
    private static final Set<String> TEXT = Set.of("txt", "log", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml",
        "toml", "ini", "conf", "env",
        "html", "htm", "css", "scss", "js", "jsx", "ts", "tsx", "java", "kt", "py", "sh", "bash", "ps1", "sql", "go",
        "rs", "c", "h", "cpp", "svg", "srt", "vtt",
        "gitignore", "dockerignore", "editorconfig", "npmrc", "npmignore");
    private static final Set<String> OFFICE = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "rtf");
    private static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg",
        "image/jpeg", "gif", "image/gif", "webp", "image/webp", "avif", "image/avif", "bmp", "image/bmp");
    private static final Map<String, String> VIDEOS = Map.of("mp4", "video/mp4", "webm", "video/webm", "ogv",
        "video/ogg", "mov", "video/quicktime");
    private static final Map<String, String> AUDIO = Map.of("mp3", "audio/mpeg", "wav", "audio/wav", "ogg", "audio/ogg",
        "m4a", "audio/mp4", "flac", "audio/flac");

    private FilePreviewMapper() {
    }

    public static ConversationFileView view(String id, String name, String path, boolean directory, String source,
                                            String mediaType, long size, String modified, String revision) {
        String extension = extension(name), kind = "unsupported";
        String type = mediaType == null || mediaType.isBlank() ? MediaTypeFactory.getMediaType(name)
            .map(Object::toString).orElse("application/octet-stream") : mediaType;
        if (!directory) {
            if (Set.of("md", "markdown", "mdown").contains(extension)) {
                kind = "markdown";
                type = "text/markdown";
            } else if (OFFICE.contains(extension)) {
                kind = "office";
            } else if (extension.equals("pdf")) {
                kind = "pdf";
                type = "application/pdf";
            } else if (TEXT.contains(extension) || Set.of("readme", "license", "dockerfile", "makefile")
                .contains(name.toLowerCase(Locale.ROOT))
                || type.startsWith("text/") || Set.of("application/json", "application/xml").contains(type)) {
                kind = "text";
                type = "text/plain";
            } else if (IMAGES.containsKey(extension)) {
                kind = "image";
                type = IMAGES.get(extension);
            } else if (VIDEOS.containsKey(extension)) {
                kind = "video";
                type = VIDEOS.get(extension);
            } else if (AUDIO.containsKey(extension)) {
                kind = "audio";
                type = AUDIO.get(extension);
            }
        }
        return new ConversationFileView(id, name, path, directory, source, type, size, modified, revision, kind);
    }

    public static String workspaceId(String path) {
        return "w." + Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    public static String workspacePath(String id) {
        try {
            if (!id.startsWith("w.") || id.length() > 3000) {
                throw new IllegalArgumentException("文件标识不正确");
            }
            String path = new String(Base64.getUrlDecoder().decode(id.substring(2)), StandardCharsets.UTF_8);
            if (!workspaceId(path).equals(id) || path.isBlank()) {
                throw new IllegalArgumentException("文件标识不正确");
            }
            return WorkspacePaths.projectLogical(path);
        } catch (IllegalArgumentException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_REFERENCE_INVALID", "文件标识不正确，请从列表重新选择。",
                failure);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
