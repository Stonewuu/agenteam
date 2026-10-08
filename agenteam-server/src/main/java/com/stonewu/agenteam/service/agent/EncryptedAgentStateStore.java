package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 每次执行独立的加密框架状态；使用 2.0.3 的版本检查防止同一状态被旧内容覆盖。
 */
public final class EncryptedAgentStateStore implements AgentStateStore {
    public record StoredState(long version, EncryptedPayload payload) {
    }

    private final Path root;
    private final String binding;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;

    public EncryptedAgentStateStore(Path root, String binding, PayloadEncryption encryption, ObjectMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.binding = binding;
        this.encryption = encryption;
        this.json = json;
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public synchronized void save(String user, String session, String key, State value) {
        saveIfVersion(user, session, key, value, UNVERSIONED);
    }

    @Override
    public synchronized long saveIfVersion(String user, String session, String key, State value, long expected) {
        Path path = statePath(user, session, key, false);
        var previous = read(path);
        long current = previous == null ? 0 : previous.version();
        if (expected != UNVERSIONED && expected != current) {
            return UNVERSIONED;
        }
        long next = Math.addExact(current, 1);
        write(path, next, JsonUtils.getJsonCodec().toJson(value));
        return next;
    }

    @Override
    public synchronized void save(String user, String session, String key, List<? extends State> values) {
        Path path = statePath(user, session, key, true);
        var previous = read(path);
        write(path, previous == null ? 1 : Math.addExact(previous.version(), 1),
            JsonUtils.getJsonCodec().toJson(values));
    }

    @Override
    public synchronized <T extends State> Optional<T> get(String user, String session, String key, Class<T> type) {
        return Optional.ofNullable(getVersioned(user, session, key, type).value());
    }

    @Override
    public synchronized <T extends State> VersionedState<T> getVersioned(String user, String session, String key,
                                                                         Class<T> type) {
        Path path = statePath(user, session, key, false);
        var stored = read(path);
        return stored == null ? new VersionedState<>(null, 0)
            : new VersionedState<>(JsonUtils.getJsonCodec().fromJson(decrypt(path, stored), type), stored.version());
    }

    @Override
    public synchronized <T extends State> List<T> getList(String user, String session, String key, Class<T> type) {
        Path path = statePath(user, session, key, true);
        var stored = read(path);
        if (stored == null) {
            return List.of();
        }
        try {
            var values = json.readTree(decrypt(path, stored));
            if (!values.isArray()) {
                throw new IllegalStateException("框架状态不是所需的列表");
            }
            var result = new ArrayList<T>();
            for (var value : values) {
                result.add(JsonUtils.getJsonCodec().fromJson(value.toString(), type));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("已保存的框架状态无法读取", error);
        }
    }

    @Override
    public synchronized boolean exists(String user, String session) {
        Path directory = sessionPath(user, session);
        if (!Files.exists(directory)) {
            return false;
        }
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException error) {
            throw new UncheckedIOException("无法检查框架状态", error);
        }
    }

    @Override
    public synchronized void delete(String user, String session, String key) {
        try {
            Files.deleteIfExists(statePath(user, session, key, false));
            Files.deleteIfExists(statePath(user, session, key, true));
        } catch (IOException error) {
            throw new UncheckedIOException("无法清理指定框架状态", error);
        }
    }

    @Override
    public synchronized void delete(String user, String session) {
        Path directory = sessionPath(user, session);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json")) {
                    Files.delete(path);
                }
            }
            Files.delete(directory);
        } catch (IOException error) {
            throw new UncheckedIOException("无法清理指定会话的框架状态", error);
        }
    }

    @Override
    public synchronized Set<String> listSessionIds(String user) {
        Path directory = root.resolve(component(user));
        if (!Files.exists(directory)) {
            return Set.of();
        }
        try (var paths = Files.list(directory)) {
            var result = new HashSet<String>();
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("v-")) {
                    result.add(new String(Base64.getUrlDecoder().decode(name.substring(2)), StandardCharsets.UTF_8));
                }
            }
            return Set.copyOf(result);
        } catch (IOException error) {
            throw new UncheckedIOException("无法读取框架会话列表", error);
        }
    }

    public synchronized Map<String, String> manifest() {
        if (!Files.exists(root)) {
            return Map.of();
        }
        try (var files = Files.walk(root)) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Path path : files.filter(Files::isRegularFile)
                .filter(value -> !root.relativize(value).startsWith("checkpoints"))
                .filter(value -> value.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("框架状态不能使用符号链接");
                }
                values.put(root.relativize(path).toString().replace('\\', '/'), digest(Files.readAllBytes(path)));
            }
            return Map.copyOf(values);
        } catch (IOException error) {
            throw new UncheckedIOException("无法确认已保存的框架状态", error);
        }
    }

    public synchronized boolean matches(Map<String, String> expected) {
        return expected.equals(manifest());
    }

    /**
     * 重新加密复制到另一次领取或独立检查点，不让后续框架保存改变已提交的副本。
     */
    public synchronized void copyTo(EncryptedAgentStateStore target) {
        for (String relative : manifest().keySet()) {
            if (relative.startsWith("checkpoints/")) {
                continue;
            }
            Path source = root.resolve(relative).normalize();
            Path destination = target.root.resolve(relative).normalize();
            if (!source.startsWith(root) || !destination.startsWith(target.root)) {
                throw new IllegalStateException("框架状态路径超出本次执行目录");
            }
            var saved = read(source);
            target.write(destination, saved.version(), decrypt(source, saved));
        }
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("无法计算框架状态摘要", impossible);
        }
    }

    private StoredState read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return json.readValue(Files.readAllBytes(path), StoredState.class);
        } catch (IOException error) {
            throw new UncheckedIOException("已保存的框架状态无法读取", error);
        }
    }

    private String decrypt(Path path, StoredState stored) {
        return encryption.decrypt(stored.payload(), binding + ":" + root.relativize(path).toString().replace('\\', '/'),
            String.class);
    }

    private void write(Path path, long version, String value) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            var payload = encryption.encrypt(value,
                binding + ":" + root.relativize(path).toString().replace('\\', '/'));
            temporary = Files.createTempFile(path.getParent(), "pending-", ".tmp");
            Files.write(temporary, json.writeValueAsBytes(new StoredState(version, payload)));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("框架状态无法保存", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) { /* 下次目录清理会处理未使用的临时文件。 */ }
            }
        }
    }

    private Path statePath(String user, String session, String key, boolean list) {
        return sessionPath(user, session).resolve(component(key) + (list ? "-list.json" : ".json"));
    }

    private Path sessionPath(String user, String session) {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("框架会话编号不能为空");
        }
        return root.resolve(component(user)).resolve(component(session));
    }

    public static String component(String value) {
        return value == null ? "empty" : "v-" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
