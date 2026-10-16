package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 可直接在 Linux 容器运行，检查等待者退出后原有的跨进程文件锁仍有效。 */
public final class SandboxLockProcessCheck {
    private SandboxLockProcessCheck() {
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        var capacity = new SandboxCapacity(2, directory.toString());
        try (var control = new ToolCallControl(() -> {
        })) {
            if (arguments.length > 1) {
                if (arguments[1].equals("blocked")) {
                    requireBlocked(capacity, control);
                } else {
                    try (var permit = capacity.acquire(Duration.ofSeconds(2), control)) {
                        System.out.println("原命令完成后已取得共享名额");
                    }
                }
                return;
            }
            try (var permit = capacity.acquire(Duration.ofSeconds(5), control)) {
                requireBlocked(new SandboxCapacity(2, directory.toString()), control);
                // 不能仅检查 FileLock.isValid；Linux 中句柄仍有效时，系统锁也可能已被其他句柄关闭。
                probe(directory, "blocked");
            }
            probe(directory, "released");
        }
    }

    private static void requireBlocked(SandboxCapacity capacity, ToolCallControl control) {
        try (var permit = capacity.acquire(Duration.ofMillis(200), control)) {
            throw new IllegalStateException("原命令尚未结束，其他调用却取得了共享名额");
        } catch (ApiException error) {
            if (!error.code().equals("SANDBOX_QUEUE_TIMEOUT")) {
                throw error;
            }
        }
    }

    private static void probe(Path directory, String mode) throws Exception {
        Path arguments = Files.createTempFile(directory, "lock-process-", ".args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-Xmx48m\n-XX:ActiveProcessorCount=1\n-XX:TieredStopAtLevel=1\n-cp\n" + quote(classpath) + "\n" + SandboxLockProcessCheck.class.getName()
            + "\n" + quote(directory.toString()) + "\n" + mode + "\n");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "@" + arguments).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("独立文件锁检查进程未结束");
            }
            if (child.exitValue() != 0) {
                throw new IllegalStateException("跨进程文件锁检查失败："
                    + new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(arguments);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"";
    }
}
