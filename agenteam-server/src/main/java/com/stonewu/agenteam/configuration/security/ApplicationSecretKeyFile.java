package com.stonewu.agenteam.configuration.security;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw new IllegalArgumentException(Messages.text("error.security.applicationSecretKeyFile.loadOrCreate.1"));
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
                LOG.info(Messages.log("log.security.applicationSecretKeyFile.loadOrCreate.1"), path);
                return keys;
            }
        } catch (IOException failure) {
            throw new IllegalStateException(Messages.text("error.security.applicationSecretKeyFile.loadOrCreate.2", path), failure);
        }
    }

    private static Keys read(Path path) throws IOException {
        if (Files.size(path) > 16384) {
            throw new IOException(Messages.text("error.security.applicationSecretKeyFile.read.1"));
        }
        var values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        }
        String signing = values.getProperty("request-signing-key", ""), encryption = values.getProperty(
            "encryption-keys", "");
        if (signing.isBlank() || encryption.isBlank()) {
            throw new IOException(Messages.text("error.security.applicationSecretKeyFile.read.2"));
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
                values.store(output, Messages.text("text.security.applicationSecretKeyFile.write.1"));
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
                LOG.warn(Messages.log("log.security.applicationSecretKeyFile.write.1"), temporary, failure);
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
