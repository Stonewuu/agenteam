package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.*;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 框架文件接口使用同一份受限目录；命令执行独立交给容器，从不继承主机命令能力。
 */
public final class WorkspaceFilesystem implements AbstractFilesystem {
    private final Path root;
    private final WorkspaceSettings settings;
    private final Runnable active;
    private final boolean writable;
    private final boolean project;
    private Runnable beforeWrite = () -> {
    };

    public WorkspaceFilesystem(Path root, WorkspaceSettings settings, Runnable active, boolean writable) {
        this(root, settings, active, writable, false);
    }

    public WorkspaceFilesystem(Path root, WorkspaceSettings settings, Runnable active, boolean writable,
                               boolean project) {
        this.root = root.toAbsolutePath().normalize();
        this.settings = settings;
        this.active = active;
        this.writable = writable;
        this.project = project;
    }

    public Path root() {
        return root;
    }

    public void requireActive() {
        active.run();
    }

    public Path resolve(String path, boolean write) {
        active.run();
        if (write && !writable) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_READ_ONLY", Messages.text("error.workspace.workspaceFilesystem.resolve.1"));
        }
        try {
            if (project) {
                String logical = WorkspacePaths.projectLogical(path);
                String protectedPath = logical.toLowerCase(Locale.ROOT);
                if (write && (logical.isEmpty() || protectedPath.equals("inputs") || protectedPath.startsWith("inputs/")
                    || protectedPath.equals("tool-results") || protectedPath.startsWith("tool-results/"))) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_READ_ONLY",
                        Messages.text("error.workspace.workspaceFilesystem.resolve.2"));
                }
                Path target = root.resolve(logical).normalize();
                WorkspacePaths.requireInside(root, target);
                return target;
            }
            return WorkspacePaths.resolve(root, path, write);
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    public byte[] bytes(String path) {
        Path file = resolve(path, false);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILE_MISSING",
                Messages.text("error.workspace.workspaceFilesystem.bytes.1"));
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            long maximum = settings.fileBytes();
            if (Files.size(file) > maximum) {
                throw WorkspacePaths.capacity();
            }
            // Java 数组按整数索引；超过可完整载入的大小时仍可通过沙盒命令处理文件。
            int readable = (int) Math.min(maximum, Integer.MAX_VALUE - 8L);
            if (Files.size(file) > readable) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "WORKSPACE_FILE_READ_TOO_LARGE",
                    Messages.text("error.workspace.workspaceFilesystem.bytes.2"));
            }
            byte[] bytes = input.readNBytes(readable);
            active.run();
            if (input.read() != -1) {
                throw WorkspacePaths.capacity();
            }
            return bytes;
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    public TextFileDocument document(String path) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(path))).toString();
        } catch (IOException cause) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKSPACE_BINARY_FILE",
                Messages.text("error.workspace.workspaceFilesystem.document.1"), cause);
        }
        if (text.indexOf('\0') >= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKSPACE_BINARY_FILE",
                Messages.text("error.workspace.workspaceFilesystem.document.2"));
        }
        String logical = project ? WorkspacePaths.projectLogical(path) : WorkspacePaths.logical(path);
        return new TextFileDocument(logical, Utf8Text.revision(logical, text), text);
    }

    public List<FileInfo> files(String path, boolean recursive) {
        Path directory = resolve(path, false);
        var result = new ArrayList<FileInfo>();
        long bytes = 0;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.walk(directory, recursive ? (project ? Integer.MAX_VALUE : 17) : 1)) {
            var iterator = paths.iterator();
            long count = 0;
            while (iterator.hasNext()) {
                active.run();
                Path item = iterator.next();
                if (item.equals(directory) && Files.isDirectory(item)) {
                    continue;
                }
                if (project && Files.isSymbolicLink(item)) {
                    // 程序依赖可以带有链接；文件接口只枚举普通文件，不能沿链接读取主机路径。
                    continue;
                }
                WorkspacePaths.requireInside(root, item);
                if (++count > settings.maximumFiles() * 2L) {
                    throw WorkspacePaths.capacity();
                }
                boolean folder = Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS);
                if (!folder && !Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    throw WorkspacePaths.invalid();
                }
                long size = folder ? 0 : Files.size(item);
                bytes += size;
                if ((!project || recursive) && (size > settings.fileBytes() || bytes > settings.workspaceBytes())) {
                    throw WorkspacePaths.capacity();
                }
                result.add(new FileInfo(root.relativize(item).toString().replace('\\', '/'), folder, size,
                    Files.getLastModifiedTime(item).toInstant().toString()));
            }
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
        result.sort(Comparator.comparing(FileInfo::path));
        return List.copyOf(result);
    }

    public void validate() {
        var entries = files("/", true);
        if (entries.stream().filter(item -> !item.isDirectory()).count() > settings.maximumFiles()) {
            throw WorkspacePaths.capacity();
        }
        for (var entry : entries) {
            if (project) {
                WorkspacePaths.projectLogical(entry.path());
            } else {
                WorkspacePaths.logical(entry.path());
            }
        }
    }

    @Override
    public LsResult ls(RuntimeContext context, String path) {
        return LsResult.success(files(path, false));
    }

    @Override
    public ReadResult read(RuntimeContext context, String path, int offset, int limit) {
        if (offset < 0 || limit <= 0 || limit > 2000) {
            return ReadResult.fail(Messages.text("error.workspace.workspaceFilesystem.read.1"));
        }
        return ReadResult.success(
            new FileData(document(path).read(offset + 1, offset + limit, null, limit, 8192).content(), "utf-8"));
    }

    @Override
    public WriteResult write(RuntimeContext context, String path, String content) {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8), false);
        return WriteResult.ok(path);
    }

    private void writeBytes(String path, byte[] bytes, boolean replace) {
        Path file = resolve(path, true);
        if (bytes.length > settings.fileBytes()) {
            throw WorkspacePaths.capacity();
        }
        try {
            if (!replace && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_FILE_EXISTS",
                    Messages.text("error.workspace.workspaceFilesystem.writeBytes.1"));
            }
            if (project) {
                writeProjectFile(file, bytes, replace);
                return;
            }
            Files.createDirectories(file.getParent());
            WorkspacePaths.requireInside(root, file);
            Files.write(file, bytes, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.WRITE,
                replace ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE_NEW);
            active.run();
            validate();
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    public void beforeWrite(Runnable action) {
        this.beforeWrite = action;
    }

    private void writeProjectFile(Path file, byte[] bytes, boolean replace) throws IOException {
        var entries = files("/", true);
        long previous = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
        long total = entries.stream().mapToLong(FileInfo::size).sum() - previous + bytes.length;
        long count = entries.stream().filter(entry -> !entry.isDirectory()).count() + (replace ? 0 : 1);
        if (total > settings.workspaceBytes() || count > settings.maximumFiles()) {
            throw WorkspacePaths.capacity();
        }
        Files.createDirectories(file.getParent());
        WorkspacePaths.requireInside(root, file);
        Path temporary = file.resolveSibling(".write-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            active.run();
            beforeWrite.run();
            if (replace) {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public EditResult edit(RuntimeContext context, String path, String oldText, String newText, boolean all) {
        resolve(path, true);
        if (oldText == null || oldText.isEmpty() || oldText.equals(newText)) {
            return EditResult.fail(Messages.text("error.workspace.workspaceFilesystem.edit.1"));
        }
        String original = document(path).window(0, (int) Math.min(Integer.MAX_VALUE, settings.fileBytes()), false).content();
        int count = 0, from = 0;
        while ((from = original.indexOf(oldText, from)) >= 0) {
            count++;
            from += oldText.length();
        }
        if (count == 0 || !all && count != 1) {
            return EditResult.fail(
                count == 0 ? Messages.text("error.workspace.workspaceFilesystem.edit.2") : Messages.text("error.workspace.workspaceFilesystem.edit.3"));
        }
        long size = Utf8Text.size(original) + (long) count * (Utf8Text.size(newText) - Utf8Text.size(oldText));
        if (size > settings.fileBytes()) {
            throw WorkspacePaths.capacity();
        }
        writeBytes(path, original.replace(oldText, newText).getBytes(StandardCharsets.UTF_8), true);
        return EditResult.ok(path, count);
    }

    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
        var matches = new ArrayList<GrepMatch>();
        for (var file : glob(context, glob == null ? "*" : glob, path).matches()) {
            if (!file.isDirectory()) {
                var found = document(file.path()).search(pattern, false, false, 1, 100, 0, 0, 8192, () -> {
                    active.run();
                    return false;
                });
                for (var match : found.matches()) {
                    matches.add(new GrepMatch(file.path(), match.line(), match.excerpt().content()));
                }
                if (!found.complete() || matches.size() > 100) {
                    return GrepResult.fail(Messages.text("error.workspace.workspaceFilesystem.grep.1"));
                }
            }
        }
        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String path) {
        var glob = new WorkspaceFileGlob(pattern);
        return GlobResult.success(files(path, true).stream().filter(file -> glob.matches(file.path())).toList());
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
        return files.stream().map(file -> {
            writeBytes(file.getKey(), file.getValue(), false);
            return FileUploadResponse.success(file.getKey());
        }).toList();
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        long total = 0;
        var result = new ArrayList<FileDownloadResponse>();
        for (String path : paths) {
            var content = bytes(path);
            total += content.length;
            if (total > settings.fileBytes()) {
                throw WorkspacePaths.capacity();
            }
            result.add(FileDownloadResponse.success(path, content));
        }
        return List.copyOf(result);
    }

    @Override
    public WriteResult delete(RuntimeContext context, String path) {
        Path target = resolve(path, true);
        try {
            Files.deleteIfExists(target);
            return WriteResult.ok(path);
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    @Override
    public WriteResult move(RuntimeContext context, String from, String to) {
        Path source = resolve(from, true), target = resolve(to, true);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return WriteResult.fail(Messages.text("error.workspace.workspaceFilesystem.move.1"));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            return WriteResult.ok(to);
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    @Override
    public boolean exists(RuntimeContext context, String path) {
        return Files.exists(resolve(path, false), LinkOption.NOFOLLOW_LINKS);
    }
}
