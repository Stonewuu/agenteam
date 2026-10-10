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

/** 低内存主机上的执行服务共用一个文件锁；进程退出后操作系统自动释放，不依赖过期时间。 */
final class SandboxHostPermit implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SandboxHostPermit(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SandboxHostPermit acquire(Path root, long deadline, ToolCallControl control) throws IOException {
        Files.createDirectories(root);
        var channel = FileChannel.open(root.resolve("commands.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean acquired = false;
        try {
            while (true) {
                control.requireActive();
                if (System.nanoTime() >= deadline) {
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_QUEUE_TIMEOUT",
                        "执行环境仍在处理其他任务，请稍后重试。");
                }
                try {
                    var lock = channel.tryLock();
                    if (lock != null) {
                        acquired = true;
                        return new SandboxHostPermit(channel, lock);
                    }
                } catch (OverlappingFileLockException busy) {
                    // 同一进程中的另一执行入口持有该名额，也必须等待。
                }
                control.pause(Duration.ofMillis(50));
            }
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            channel.close();
        }
    }
}
