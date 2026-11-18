package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 删除时先移出用户可见目录，数据库事务提交后清理，回滚时放回原处。 */
public final class WorkspaceFileChanges implements TransactionSynchronization {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceFileChanges.class);
    private final ProjectWorkspaceLayout layout;
    private final String workspace;
    private final UserWorkspaceLock lock;
    private final List<Path> created = new ArrayList<>();
    private final Map<Path, Path> moved = new LinkedHashMap<>();

    public WorkspaceFileChanges(ProjectWorkspaceLayout layout, String workspace) {
        this(layout, workspace, null);
    }

    private WorkspaceFileChanges(ProjectWorkspaceLayout layout, String workspace, UserWorkspaceLock lock) {
        this.layout = layout;
        this.workspace = workspace;
        this.lock = lock;
        TransactionSynchronizationManager.registerSynchronization(this);
    }

    /** 删除或写入文件后，等数据库提交或回滚完成，再允许其他文件操作进入。 */
    public static WorkspaceFileChanges locked(ProjectWorkspaceLayout layout, String workspace) throws IOException {
        try (var control = new ToolCallControl(() -> {
        })) {
            return new WorkspaceFileChanges(layout, workspace, UserWorkspaceLock.acquire(layout, workspace, Duration.ofSeconds(15), control));
        }
    }

    public void created(Path path) {
        created.add(path);
    }

    public void remove(Path path) throws IOException {
        WorkspacePaths.requireInside(layout.workspace(workspace), path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path pending = layout.control(workspace).resolve("delete-" + UUID.randomUUID());
            WorkspacePaths.requireInside(layout.root(), pending);
            Files.move(path, pending, StandardCopyOption.ATOMIC_MOVE);
            moved.put(path, pending);
        }
    }

    @Override
    public void afterCompletion(int status) {
        try {
            finish(status);
        } finally {
            if (lock != null) {
                try {
                    lock.close();
                } catch (IOException failure) {
                    LOG.error("Could not release workspace lock; workspaceId={}", workspace, failure);
                }
            }
        }
    }

    private void finish(int status) {
        for (var entry : moved.entrySet()) {
            try {
                if (status == STATUS_COMMITTED) {
                    WorkspaceStore.deleteTree(layout.control(workspace), entry.getValue());
                } else if (status == STATUS_ROLLED_BACK) {
                    WorkspacePaths.requireInside(layout.root(), entry.getKey());
                    Files.move(entry.getValue(), entry.getKey());
                }
            } catch (IOException | RuntimeException failure) {
                LOG.error("Could not finish workspace file removal; workspaceId={}, pending={}", workspace,
                    entry.getValue().getFileName(), failure);
            }
        }
        if (status == STATUS_ROLLED_BACK) {
            for (Path path : created.reversed()) {
                try {
                    WorkspacePaths.requireInside(layout.root(), path);
                    Files.deleteIfExists(path);
                } catch (DirectoryNotEmptyException occupied) {
                    // 其他操作已在这个目录放入文件，需要保留。
                } catch (IOException | RuntimeException failure) {
                    LOG.error("Could not roll back workspace entry creation; workspaceId={}", workspace, failure);
                }
            }
            if (!created.isEmpty() || !moved.isEmpty()) {
                try {
                    layout.recordFileChange(workspace);
                } catch (IOException failure) {
                    LOG.error("Could not update the workspace revision after rollback; workspaceId={}", workspace, failure);
                }
            }
        }
    }
}
