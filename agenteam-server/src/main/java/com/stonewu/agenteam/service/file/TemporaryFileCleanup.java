package com.stonewu.agenteam.service.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 临时文件由调用方确定归属，删除时有限等待操作系统释放文件占用。
 */
public final class TemporaryFileCleanup {
    private TemporaryFileCleanup() {
    }

    public static void delete(Path file) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    Files.deleteIfExists(file);
                    return;
                } catch (IOException occupied) {
                    if (System.nanoTime() >= deadline) {
                        throw occupied;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                    interrupted |= Thread.interrupted();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
