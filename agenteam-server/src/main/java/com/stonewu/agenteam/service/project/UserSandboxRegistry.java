package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.model.project.entity.ProjectCommandRecord;
import com.stonewu.agenteam.model.project.entity.SandboxRunUsage;
import com.stonewu.agenteam.model.project.entity.UserSandboxState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制记录使用共享持久目录；状态变更由调用者持有短时间的容器状态锁。
 */
@Component
public class UserSandboxRegistry {
    private final ProjectWorkspaceLayout layout;

    public UserSandboxRegistry(ProjectWorkspaceLayout layout) {
        this.layout = layout;
    }

    public UserSandboxState state(String workspace) throws IOException {
        Path path = layout.control(workspace).resolve("container-state.json");
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? layout.read(path, UserSandboxState.class) : null;
    }

    public void save(UserSandboxState value) throws IOException {
        layout.write(layout.control(value.project().workspaceId()).resolve("container-state.json"), value);
    }

    public ProjectCommandRecord command(String workspace, String call) throws IOException {
        Path path = commandPath(workspace, call);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? layout.read(path, ProjectCommandRecord.class) : null;
    }

    public void save(ProjectCommandRecord value) throws IOException {
        String workspace = value.project().workspaceId();
        Path receipt = commandPath(workspace, value.callId());
        Files.createDirectories(receipt.getParent());
        layout.write(receipt, value);
        Path active = layout.control(workspace).resolve("active-commands").resolve(value.callId() + ".json");
        if (value.result() != null || "interrupted".equals(value.status())) {
            Files.deleteIfExists(active);
        } else {
            Files.createDirectories(active.getParent());
            layout.write(active, value);
        }
    }

    public List<ProjectCommandRecord> active(String workspace) throws IOException {
        return readDirectory(layout.control(workspace).resolve("active-commands"), ProjectCommandRecord.class);
    }

    public List<SandboxRunUsage> usages(String workspace) throws IOException {
        return readDirectory(layout.control(workspace).resolve("run-usages"), SandboxRunUsage.class);
    }

    public void save(SandboxRunUsage value) throws IOException {
        Path path = usagePath(value.project().workspaceId(), value.runId(), value.leaseVersion());
        Files.createDirectories(path.getParent());
        layout.write(path, value);
    }

    public void remove(SandboxRunUsage value) throws IOException {
        Files.deleteIfExists(usagePath(value.project().workspaceId(), value.runId(), value.leaseVersion()));
    }

    public List<String> workspaces() throws IOException {
        if (!Files.isDirectory(layout.root(), LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(layout.root())) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> path.getFileName().toString()).filter(name -> name.matches("[0-9a-f]{64}")).sorted()
                .toList();
        }
    }

    public void markActivity(String workspace, long now) throws IOException {
        UserSandboxState previous = state(workspace);
        if (previous != null) {
            save(new UserSandboxState(previous.project(), previous.containerId(), previous.generation(),
                previous.daemonId(),
                previous.status(), now, now));
        }
    }

    private Path commandPath(String workspace, String call) {
        requireIdentifier(call);
        return layout.control(workspace).resolve("commands").resolve(call + ".json");
    }

    private Path usagePath(String workspace, String run, long version) {
        requireIdentifier(run);
        return layout.control(workspace).resolve("run-usages").resolve(run + "-" + version + ".json");
    }

    private void requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) {
            throw ProjectWorkspaceLayout.unavailable();
        }
    }

    private <T> List<T> readDirectory(Path directory, Class<T> type) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            var result = new ArrayList<T>();
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                result.add(layout.read(path, type));
            }
            return List.copyOf(result);
        }
    }
}
