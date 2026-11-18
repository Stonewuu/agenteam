package com.stonewu.agenteam.service.environment;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Map;

/** 统计用户可见目录下的普通文件；目录和链接不计入文件数量或大小。 */
public record EnvironmentFileUsage(long fileCount, long totalBytes, long directoryCount) {
    public static EnvironmentFileUsage read(Path root) throws IOException {
        return read(root, null);
    }

    static EnvironmentFileUsage read(Path root, Map<String, Long> folderSizes) throws IOException {
        class Counter extends SimpleFileVisitor<Path> {
            long files;
            long bytes;
            long directories;
            final ArrayDeque<Long> starts = new ArrayDeque<>();
            boolean collectFolders = folderSizes != null;

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                if (!root.equals(path)) {
                    directories++;
                }
                if (collectFolders) {
                    if (directories > EnvironmentFileStatistics.MAX_CACHED_FOLDERS) {
                        folderSizes.clear();
                        starts.clear();
                        collectFolders = false;
                    } else {
                        starts.push(bytes);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (collectFolders) {
                    folderSizes.put(root.relativize(path).toString().replace('\\', '/'), bytes - starts.pop());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files++;
                    bytes += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                if (!path.equals(root) && failure instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }
        }
        var counter = new Counter();
        Files.walkFileTree(root, counter);
        return new EnvironmentFileUsage(counter.files, counter.bytes, counter.directories);
    }
}
