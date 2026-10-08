package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 内容先在有大小限制的临时文件中校验，再写入私有存储；不占用数据库事务。
 */
@Component
public class FileContentStorage {
    private final Path temporaryRoot;
    private final FileObjectStore objects;
    private String orphanCursor;

    public FileContentStorage(
        @Value("${files.temporary-root:${files.root:.agenteam/files}/.temporary}") String directory,
        FileObjectStore objects) {
        temporaryRoot = Path.of(directory).toAbsolutePath().normalize();
        this.objects = objects;
    }

    public record Stored(String key, long size, String sha256) {
    }

    public Stored write(String enterprise, InputStream input, long limit) {
        if (!enterprise.matches("[A-Za-z0-9_-]{1,100}") || limit < 1 || limit > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("文件存储范围不正确");
        }
        String key = enterprise + "/" + UUID.randomUUID() + ".data";
        Path temporary = null;
        try {
            Files.createDirectories(temporaryRoot);
            if (Files.isSymbolicLink(temporaryRoot)) {
                throw new IOException("临时文件目录不能为链接");
            }
            temporary = Files.createTempFile(temporaryRoot, "upload-", ".part");
            MessageDigest digest = digest();
            long count = 0;
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("文件写入已取消");
                    }
                    if (length == 0) {
                        continue;
                    }
                    count += length;
                    if (count > limit) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                            "文件超过当前用途允许的大小。");
                    }
                    digest.update(buffer, 0, length);
                    output.write(buffer, 0, length);
                }
            }
            objects.put(key, temporary);
            return new Stored(key, count, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        } finally {
            if (temporary != null) {
                try {
                    TemporaryFileCleanup.delete(temporary);
                } catch (IOException failed) {
                    throw FileStorageKeys.storageUnavailable(failed);
                }
            }
        }
    }

    public InputStream open(FileRecord file) {
        FileStorageKeys.require(file.storageKey());
        if (!file.storageKey().startsWith(file.enterpriseId() + "/")) {
            throw FileStorageKeys.unavailable();
        }
        return objects.open(file.storageKey());
    }

    public Stored verify(FileRecord file) {
        try (var input = open(file)) {
            MessageDigest digest = digest();
            long size = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                size += read;
                if (size > 100L * 1024 * 1024) {
                    throw FileStorageKeys.unavailable();
                }
                digest.update(buffer, 0, read);
            }
            return new Stored(file.storageKey(), size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    public void delete(String key) {
        FileStorageKeys.require(key);
        objects.delete(key);
    }

    public Path prepareImportFile() {
        try {
            Files.createDirectories(temporaryRoot);
            if (Files.isSymbolicLink(temporaryRoot)) {
                throw new IOException("临时文件目录不能为链接");
            }
            return Files.createTempFile(temporaryRoot, "import-", ".part");
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    public synchronized int cleanOrphans(Instant before, Predicate<String> referenced, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("文件清理批次超出范围");
        }
        int removed = 0;
        var page = objects.list(orphanCursor, limit);
        orphanCursor = page.nextCursor();
        for (var item : page.items()) {
            String key = item.key(), actual = key.endsWith(".part") ? key.substring(0, key.length() - 5) : key;
            if (FileStorageKeys.valid(actual) && item.modifiedAt().isBefore(before) && !referenced.test(actual)) {
                objects.delete(key);
                removed++;
            }
        }
        return removed + cleanTemporary(before, limit);
    }

    private int cleanTemporary(Instant before, int limit) {
        if (!Files.isDirectory(temporaryRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var files = Files.find(temporaryRoot, 1,
            (path, attributes) -> attributes.isRegularFile() && !attributes.isSymbolicLink()
                && path.getFileName().toString()
                .matches("(?:upload|import)-[0-9]+\\.part") && attributes.lastModifiedTime().toInstant()
                .isBefore(before))) {
            int removed = 0;
            for (var file : files.limit(limit).toList()) {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            }
            return removed;
        } catch (IOException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", impossible);
        }
    }
}
