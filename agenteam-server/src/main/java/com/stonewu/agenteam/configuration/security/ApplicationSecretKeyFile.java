package com.stonewu.agenteam.configuration.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * 仅在缺少显式配置时读取本机密钥，首次创建完成后不覆盖已有文件。
 */
final class ApplicationSecretKeyFile {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationSecretKeyFile.class);

    private ApplicationSecretKeyFile() {
    }

    record Keys(String signingKey, String encryptionKeys) {
    }

    static synchronized Keys loadOrCreate(String configuredPath, String activeVersion) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("应用密钥文件路径不能为空");
        }
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
            // 线程间同步与文件锁共同保证并发启动读取同一份密钥。
            try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                if (Files.exists(path)) {
                    return read(path);
                }
                SecureRandom random = new SecureRandom();
                var keys = new Keys(generate(random),
                    new ObjectMapper().writeValueAsString(Map.of(activeVersion, generate(random))));
                write(path, keys);
                LOG.info("已自动生成应用密钥并保存到 {}，后续启动将复用此文件。", path);
                return keys;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("应用密钥文件无法读取或创建，请检查目录权限：" + path, failure);
        }
    }

    private static Keys read(Path path) throws IOException {
        if (Files.size(path) > 16384) {
            throw new IOException("应用密钥文件内容超出允许大小");
        }
        var values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        }
        String signing = values.getProperty("request-signing-key", ""), encryption = values.getProperty(
            "encryption-keys", "");
        if (signing.isBlank() || encryption.isBlank()) {
            throw new IOException("应用密钥文件不完整，请恢复原文件，不能用新密钥覆盖");
        }
        return new Keys(signing, encryption);
    }

    private static void write(Path path, Keys keys) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "application-keys-", ".tmp");
        try {
            restrictAccess(temporary);
            var values = new Properties();
            values.setProperty("request-signing-key", keys.signingKey());
            values.setProperty("encryption-keys", keys.encryptionKeys());
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, "应用自动生成的密钥，请与运行数据一起保留");
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException failure) {
                LOG.warn("应用密钥临时文件暂未删除，文件位置 {}", temporary, failure);
            }
        }
    }

    private static void restrictAccess(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
        }
    }

    private static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
