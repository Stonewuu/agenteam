package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持久挂载保留实际写入；容量检查允许已有超限目录执行清理命令。
 */
final class ProjectCapacityMonitor {
    private final Path root;
    private final WorkspaceSettings settings;
    private final AtomicBoolean stopped;

    ProjectCapacityMonitor(Path root, WorkspaceSettings settings, AtomicBoolean stopped) {
        this.root = root;
        this.settings = settings;
        this.stopped = stopped;
    }

    record Usage(long bytes, long largest, int entries) {
        boolean increasedFrom(Usage before) {
            return bytes > before.bytes || largest > before.largest || entries > before.entries;
        }
    }

    Usage usage() throws IOException {
        long[] sizes = {0, 0, 0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                sizes[2]++;
                return stopped.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                sizes[0] += attributes.size();
                sizes[1] = Math.max(sizes[1], attributes.size());
                sizes[2]++;
                return stopped.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (!Files.exists(file)) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }
        });
        return new Usage(sizes[0], sizes[1], (int) Math.min(Integer.MAX_VALUE, sizes[2]));
    }

    boolean exceeded(Usage usage) {
        return usage.bytes > settings.workspaceBytes() || usage.largest > settings.fileBytes() || usage.entries > settings.maximumFiles() * 2;
    }
}
