package com.stonewu.agenteam.support;

import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 独立工作进程仅获得当前测试需要的连接和密钥，不复制完整机器环境配置。
 */
public final class RunProcessTestFixture {
    private RunProcessTestFixture() {
    }

    public static Process start(Environment environment, Path directory, String enterprise, String run, String logName) throws IOException {
        var values = new Properties();
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.data.redis.host",
            "spring.data.redis.port", "spring.data.redis.password", "spring.data.redis.database", "spring.session.redis.namespace",
            "spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password", "spring.mail.protocol",
            "spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.enable", "spring.mail.properties.mail.smtp.starttls.required",
            "agenteam.mail.from", "agenteam.web.public-base-url", "agenteam.auth.setup-credential",
            "agenteam.security.request-signing-key", "agenteam.security.encryption-keys", "agenteam.security.active-encryption-key",
            "network.allowed-private-origins", "execution.workspace-root", "execution.state-root")) {
            String value = environment.getProperty(key);
            if (value != null) {
                values.setProperty(key, value);
            }
        }
        values.setProperty("server.address", "127.0.0.1");
        values.setProperty("server.port", "0");
        values.setProperty("spring.profiles.active", "isolated-restart");
        values.setProperty("agenteam.models.config-file", "");
        for (String key : List.of("agenteam.maintenance.worker-enabled", "agenteam.mail.worker-enabled", "execution.worker.enabled", "execution.events.worker-enabled",
            "files.inspection.enabled", "files.retention.enabled")) {
            values.setProperty(key, "false");
        }
        values.setProperty("files.root", directory.resolve("files").toString());
        Path properties = directory.resolve(logName + ".properties");
        try (var output = Files.newOutputStream(properties)) {
            values.store(output, "仅用于当前隔离测试");
        }
        return start(properties, Path.of("target", logName + ".log"), enterprise, run);
    }

    public static Process start(Path properties, Path log, String enterprise, String run) throws IOException {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var arguments = List.of("-cp", classpath, RestartedRunWorker.class.getName(), properties.toString(), enterprise, run);
        Path argumentFile = properties.resolveSibling(properties.getFileName() + ".args");
        // 依赖增加后完整类路径可能超过 Windows 命令行长度，交给 Java 从参数文件读取。
        Files.writeString(argumentFile, String.join("\n", arguments.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()));
        return new ProcessBuilder(executable, "@" + argumentFile)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
}
