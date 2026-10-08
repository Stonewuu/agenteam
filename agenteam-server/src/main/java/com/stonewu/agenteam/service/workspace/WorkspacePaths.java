package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 逻辑路径仅能指向当前任务目录，不接受主机绝对路径、设备名或目录链接。
 */
public final class WorkspacePaths {
    public static final Set<String> ROOTS = Set.of("inputs", "tool-results", "work", "outputs");

    private WorkspacePaths() {
    }

    public static String logical(String path) {
        return logical(path, false);
    }

    public static String projectLogical(String path) {
        return logical(path, true);
    }

    private static String logical(String path, boolean project) {
        if (path == null || path.isBlank() || path.equals("/") || path.equals(".")) {
            return "";
        }
        if (path.equals("/workspace") || path.equals("/workspace/")) {
            return "";
        }
        String value = path.startsWith("/workspace/") ? path.substring(11) : path.startsWith("/") ? path.substring(
            1) : path;
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 512 || value.contains("\\") || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        String[] parts = value.split("/", -1);
        if (parts.length > (project ? 64 : 16) || !project && !ROOTS.contains(parts[0])) {
            throw invalid();
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(
                ".") || part.length() > 150
                || Utf8Text.size(part) > 240 || part.matches(".*[<>:\"|?*].*")) {
                throw invalid();
            }
            String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw invalid();
            }
        }
        return value;
    }

    public static Path resolve(Path root, String path, boolean write) throws IOException {
        String value = logical(path);
        if (write && !(value.startsWith("work/") || value.startsWith("outputs/"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_READ_ONLY",
                "只能修改 work 和 outputs 目录中的文件。");
        }
        Path resolved = root.resolve(value).normalize();
        requireInside(root, resolved);
        return resolved;
    }

    public static void requireInside(Path root, Path target) throws IOException {
        Path base = root.toAbsolutePath().normalize(), value = target.toAbsolutePath().normalize();
        if (!value.startsWith(base) || Files.isSymbolicLink(base)) {
            throw invalid();
        }
        Path real = base.toRealPath();
        Path current = base;
        for (Path component : base.relativize(value)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(current) || !current.toRealPath()
                .equals(real.resolve(base.relativize(current))))) {
                throw invalid();
            }
        }
    }

    public static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_PATH_INVALID",
            "文件路径不属于当前工作空间，或包含不支持的名称、目录链接。");
    }

    public static ApiException io(IOException cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_IO_FAILED",
            "工作文件暂时无法读取或保存，请稍后重试。", cause);
    }

    public static ApiException capacity() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "WORKSPACE_CAPACITY_EXCEEDED",
            "工作文件超过允许的大小或数量，请缩小本次处理范围。");
    }
}
