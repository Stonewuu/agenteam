package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件操作和容器状态分别短暂加锁，同进程先协调，避免重复打开锁文件。
 */
public final class UserWorkspaceLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, LocalLock> LOCAL = new ConcurrentHashMap<>();

    private static final class LocalLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    private final FileChannel channel;
    private final FileLock lock;
    private final LocalLock local;
    private final Path path;

    private UserWorkspaceLock(FileChannel channel, FileLock lock, LocalLock local, Path path) {
        this.channel = channel;
        this.lock = lock;
        this.local = local;
        this.path = path;
    }

    public static UserWorkspaceLock acquire(ProjectWorkspaceLayout layout, String id, Duration timeout,
                                            ToolCallControl control) throws IOException {
        return acquire(layout, id, "user.lock", timeout, control);
    }

    public static UserWorkspaceLock runtime(ProjectWorkspaceLayout layout, String id, Duration timeout,
                                            ToolCallControl control) throws IOException {
        return acquire(layout, id, "runtime.lock", timeout, control);
    }

    public static UserWorkspaceLock transition(ProjectWorkspaceLayout layout, String id, Duration timeout,
                                               ToolCallControl control) throws IOException {
        return acquire(layout, id, "container-transition.lock", timeout, control);
    }

    public static UserWorkspaceLock capacity(ProjectWorkspaceLayout layout, Duration timeout,
                                             ToolCallControl control) throws IOException {
        return acquire(layout, layout.root().resolve(".container-creation.lock"), timeout, control);
    }

    private static UserWorkspaceLock acquire(ProjectWorkspaceLayout layout, String id, String filename,
                                             Duration timeout, ToolCallControl control) throws IOException {
        return acquire(layout, layout.control(id).resolve(filename), timeout, control);
    }

    private static UserWorkspaceLock acquire(ProjectWorkspaceLayout layout, Path path, Duration timeout,
                                             ToolCallControl control) throws IOException {
        WorkspacePaths.requireInside(layout.root(), path);
        long deadline = System.nanoTime() + timeout.toNanos();
        Path key = path.toAbsolutePath().normalize();
        var local = LOCAL.compute(key, (ignored, previous) -> {
            var value = previous == null ? new LocalLock() : previous;
            value.references++;
            return value;
        });
        boolean acquired = false;
        FileChannel channel = null;
        try {
            while (!(acquired = local.lock.tryLock())) {
                await(deadline, control);
            }
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            while (true) {
                control.requireActive();
                FileLock lock = null;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    // 同一进程的另一个会话仍在使用此用户空间。
                }
                if (lock != null) {
                    return new UserWorkspaceLock(channel, lock, local, key);
                }
                await(deadline, control);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            } finally {
                if (acquired) {
                    local.lock.unlock();
                }
                releaseReference(key, local);
            }
            throw failure;
        }
    }

    private static void releaseReference(Path path, LocalLock local) {
        LOCAL.computeIfPresent(path, (ignored, current) -> {
            if (current != local) {
                throw new IllegalStateException("工作空间文件锁归属已变化");
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private static void await(long deadline, ToolCallControl control) {
        control.requireActive();
        if (System.nanoTime() >= deadline) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_BUSY", "工作空间正在由其他任务使用，请稍后重试。");
        }
        control.pause(Duration.ofMillis(20));
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            try {
                channel.close();
            } finally {
                local.lock.unlock();
                releaseReference(path, local);
            }
        }
    }
}
