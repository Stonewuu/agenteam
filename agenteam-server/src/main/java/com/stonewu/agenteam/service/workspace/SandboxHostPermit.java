package com.stonewu.agenteam.service.workspace;

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

/** 低内存主机上的执行服务共用一个文件锁；进程退出后操作系统自动释放，不依赖过期时间。 */
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

    static SandboxHostPermit acquire(Path root, long deadline, ToolCallControl control) throws IOException {
        Files.createDirectories(root);
        Path path = root.toRealPath().resolve("commands.lock");
        var local = LOCAL.computeIfAbsent(path, ignored -> new Semaphore(1, true));
        // Linux 关闭同一进程的其他文件句柄也可能释放该文件的锁，等待者不能提前打开它。
        while (!local.tryAcquire()) {
            requireActive(deadline, control);
            control.pause(Duration.ofMillis(50));
        }
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            while (true) {
                requireActive(deadline, control);
                try {
                    var lock = channel.tryLock();
                    if (lock != null) {
                        acquired = true;
                        return new SandboxHostPermit(channel, lock, local);
                    }
                } catch (OverlappingFileLockException busy) {
                    // 同一进程中的另一执行入口持有该名额，也必须等待。
                }
                control.pause(Duration.ofMillis(50));
            }
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
                "执行环境仍在处理其他任务，请稍后重试。");
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
