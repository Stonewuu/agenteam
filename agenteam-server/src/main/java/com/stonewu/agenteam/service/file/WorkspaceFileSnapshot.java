package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 在项目锁内复制单个预览文件；下载关闭后删除副本，不会长时间阻塞命令。
 */
@Component
public class WorkspaceFileSnapshot {
    private final Path root;
    private final WorkspaceSettings settings;
    private final AccountBehaviorService behavior;

    public WorkspaceFileSnapshot(
        @Value("${execution.workspace-preview-root:${agenteam.storage.root:.agenteam}/file-preview}") String root,
        WorkspaceSettings settings, AccountBehaviorService behavior) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.settings = settings;
        this.behavior = behavior;
    }

    public OpenedPreviewFile open(Path source, String id, String logical, Runnable active,
                                  String userId) throws IOException {
        Path root = behavior.workspaceRoot(this.root, userId);
        var before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) {
            throw FileAccessService.unavailable();
        }
        if (before.size() > settings.fileBytes()) {
            throw WorkspacePaths.capacity();
        }
        Files.createDirectories(root);
        Path copy = Files.createTempFile(root, "preview-", ".tmp");
        boolean opened = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = Files.newInputStream(source,
                LinkOption.NOFOLLOW_LINKS); var output = Files.newOutputStream(copy)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    active.run();
                    size += read;
                    if (size > settings.fileBytes()) {
                        throw WorkspacePaths.capacity();
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            var after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (size != before.size() || after.size() != before.size() || !after.lastModifiedTime()
                .equals(before.lastModifiedTime())) {
                throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_FILE_CHANGED", "文件正在修改，请稍后重新打开。");
            }
            var metadata = FilePreviewMapper.view(id, source.getFileName().toString(), logical, false, "workspace",
                null,
                size, before.lastModifiedTime().toInstant().toString(), HexFormat.of().formatHex(digest.digest()));
            var stream = Files.newInputStream(copy, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
            opened = true;
            return new OpenedPreviewFile(metadata, stream);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前运行环境不支持文件摘要算法", failure);
        } finally {
            if (!opened) {
                Files.deleteIfExists(copy);
            }
        }
    }
}
