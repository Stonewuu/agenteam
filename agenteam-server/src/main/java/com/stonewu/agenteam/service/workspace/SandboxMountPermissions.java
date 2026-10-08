package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 容器使用独立用户，Linux 挂载内容必须可读；上层私有目录仍控制主机访问。
 */
final class SandboxMountPermissions {
    private SandboxMountPermissions() {
    }

    static void readable(Path privateRoot, Path directory, ToolCallControl control) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                control.requireActive();
                WorkspacePaths.requireInside(privateRoot, path);
                boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                if (!folder && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw WorkspacePaths.invalid();
                }
                Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(folder ? "rwxr-xr-x" : "rw-r--r--"));
            }
        }
    }
}
