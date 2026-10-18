package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.environment.entity.EnvironmentTerminalState;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserWorkspaceLock;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;

/** 有序输入和有界输出保存在共享目录，请求换到其他应用节点也不重复执行。 */
@Component
public class EnvironmentTerminalStore {
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private final ProjectWorkspaceLayout layout;

    public EnvironmentTerminalStore(ProjectWorkspaceLayout layout) {
        this.layout = layout;
    }

    @FunctionalInterface
    public interface Operation<T> {
        T apply(EnvironmentTerminalState state) throws IOException;
    }

    public <T> T locked(ProjectLocation project, Operation<T> operation) {
        try (var control = new ToolCallControl(() -> {
        }); var lock = UserWorkspaceLock.runtime(layout, project.workspaceId(), Duration.ofSeconds(10), control)) {
            layout.requireRegistered(project);
            return operation.apply(read(project));
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public EnvironmentTerminalState read(ProjectLocation project) throws IOException {
        Path path = layout.control(project.workspaceId()).resolve("terminal.json");
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? layout.read(path, EnvironmentTerminalState.class) : null;
    }

    public void save(ProjectLocation project, EnvironmentTerminalState state) throws IOException {
        layout.write(layout.control(project.workspaceId()).resolve("terminal.json"), state);
    }

    public EnvironmentTerminalState require(EnvironmentTerminalState state, String id) {
        if (state == null || !state.id.equals(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_REPLACED", "终端连接已变化，请重新连接。");
        }
        return state;
    }

    public void resetOutput(ProjectLocation project) throws IOException {
        Files.write(outputPath(project), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
    }

    public void append(ProjectLocation project, EnvironmentTerminalState state, byte[] bytes) throws IOException {
        Path path = outputPath(project);
        Files.write(path, bytes, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        state.outputEnd += bytes.length;
        if (state.outputEnd - state.outputStart > OUTPUT_LIMIT * 2L) {
            byte[] tail = new byte[OUTPUT_LIMIT];
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(channel.size() - OUTPUT_LIMIT);
                readFully(channel, ByteBuffer.wrap(tail));
            }
            Files.write(path, tail, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            state.outputStart = state.outputEnd - OUTPUT_LIMIT;
        }
    }

    public EnvironmentTerminalView view(ProjectLocation project, EnvironmentTerminalState state, long cursor) throws IOException {
        long start = Math.max(state.outputStart, Math.min(cursor, state.outputEnd));
        byte[] bytes = new byte[(int) Math.min(65536, state.outputEnd - start)];
        if (bytes.length > 0) {
            try (var channel = FileChannel.open(outputPath(project), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(start - state.outputStart);
                readFully(channel, ByteBuffer.wrap(bytes));
            }
        }
        return new EnvironmentTerminalView(state.id, state.status, Base64.getEncoder().encodeToString(bytes), start,
            start + bytes.length, state.sequence, state.directory, state.busy, state.cols, state.rows, state.error);
    }

    private void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("终端输出未完整保存");
            }
        }
    }

    private Path outputPath(ProjectLocation project) throws IOException {
        Path path = layout.control(project.workspaceId()).resolve("terminal-output.bin");
        WorkspacePaths.requireInside(layout.root(), path);
        return path;
    }
}
