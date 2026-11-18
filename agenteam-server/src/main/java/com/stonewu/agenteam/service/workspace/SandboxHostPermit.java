package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.IntSupplier;

/** 同一主机的执行服务按配置共用文件锁；进程退出后由操作系统释放。 */
final class SandboxHostPermit implements AutoCloseable {
    private static final ConcurrentMap<Path, Semaphore> LOCAL = new ConcurrentHashMap<>();
    private final FileChannel channel;
    private final FileLock lock;
    private final Semaphore local;

    private SandboxHostPermit(FileChannel channel, FileLock lock, Semaphore local) {
        this.channel = channel;
        this.lock = lock;
        this.local = local;
    }

    static SandboxHostPermit acquire(Path root, IntSupplier maximum, long deadline, ToolCallControl control) throws IOException {
        Files.createDirectories(root);
        Path directory = root.toRealPath();
        while (true) {
            int configured = maximum.getAsInt();
            int slots = LimitValue.unlimited(configured) ? Integer.MAX_VALUE : configured;
            for (int slot = 0; slot < slots; slot++) {
                requireActive(deadline, control);
                Path path = directory.resolve(slot == 0 ? "commands.lock" : "commands-" + slot + ".lock");
                var permit = tryAcquire(path);
                if (permit != null) {
                    return permit;
                }
            }
            control.pause(Duration.ofMillis(50));
        }
    }

    private static SandboxHostPermit tryAcquire(Path path) throws IOException {
        var local = LOCAL.computeIfAbsent(path, ignored -> new Semaphore(1, true));
        // Linux 关闭同一进程的其他文件句柄也可能释放该文件的锁，等待者不能提前打开它。
        if (!local.tryAcquire()) {
            return null;
        }
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                var lock = channel.tryLock();
                if (lock != null) {
                    acquired = true;
                    return new SandboxHostPermit(channel, lock, local);
                }
            } catch (OverlappingFileLockException busy) {
                // 同一进程中的另一执行入口持有该名额，继续查找下一位置。
            }
            return null;
        } finally {
            if (!acquired) {
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } finally {
                    local.release();
                }
            }
        }
    }

    private static void requireActive(long deadline, ToolCallControl control) {
        control.requireActive();
        if (System.nanoTime() >= deadline) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_QUEUE_TIMEOUT",
                Messages.text("error.workspace.sandboxCapacity.acquire.1"));
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            try {
                channel.close();
            } finally {
                local.release();
            }
        }
    }
}
