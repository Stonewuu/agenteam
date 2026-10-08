package com.stonewu.agenteam.service.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 本机开发存储保留随机对象名和原子写入，目录中的链接不能用于越过存储范围。
 */
public final class LocalFileObjectStore implements FileObjectStore {
    private final Path root;

    public LocalFileObjectStore(String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, Path source) {
        Path target = path(key), temporary = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            verifyDirectories(target.getParent());
            Files.copy(source, temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException failed) {
                throw FileStorageKeys.storageUnavailable(failed);
            }
        }
    }

    @Override
    public InputStream open(String key) {
        Path target = path(key);
        try {
            verifyDirectories(target.getParent());
            return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failed) {
            throw FileStorageKeys.unavailable(failed);
        }
    }

    @Override
    public void delete(String key) {
        Path target = path(key);
        try {
            verifyDirectories(target.getParent());
            Files.deleteIfExists(target);
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    @Override
    public Page list(String cursor, int limit) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return new Page(List.of(), null);
        }
        long offset = cursor == null ? 0 : Long.parseLong(cursor);
        try (var found = Files.find(root, 2,
            (file, attributes) -> attributes.isRegularFile() && !attributes.isSymbolicLink())) {
            var items = found.filter(this::validCandidate).skip(offset).limit(limit).map(file -> {
                try {
                    verifyDirectories(file.getParent());
                    return new Item(relative(file),
                        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant());
                } catch (IOException failed) {
                    throw FileStorageKeys.storageUnavailable(failed);
                }
            }).toList();
            return new Page(items, items.size() < limit ? null : Long.toString(offset + items.size()));
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    private boolean validCandidate(Path file) {
        String key = relative(file);
        return FileStorageKeys.valid(key.endsWith(".part") ? key.substring(0, key.length() - 5) : key);
    }

    private String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Path path(String key) {
        if (key == null) {
            throw FileStorageKeys.unavailable();
        }
        FileStorageKeys.require(key.endsWith(".part") ? key.substring(0, key.length() - 5) : key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw FileStorageKeys.unavailable();
        }
        return resolved;
    }

    private void verifyDirectories(Path directory) throws IOException {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("文件根目录不能为链接");
        }
        for (var segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("文件目录不能为链接");
            }
        }
    }
}
