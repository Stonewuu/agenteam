package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCapacityTest {
    @TempDir
    Path directory;

    @Test
    void localOnlyCapacityRetainsConfiguredParallelismAndReleasesOnce() {
        var capacity = new SandboxCapacity(2);
        try (var control = control(); var first = capacity.acquire(Duration.ofSeconds(2), control);
             var second = capacity.acquire(Duration.ofSeconds(2), control)) {
            assertEquals("SANDBOX_QUEUE_TIMEOUT", assertThrows(ApiException.class,
                () -> capacity.acquire(Duration.ofMillis(100), control)).code());
            first.close();
            first.close();
            try (var third = capacity.acquire(Duration.ofSeconds(1), control)) {
                assertThrows(ApiException.class, () -> capacity.acquire(Duration.ofMillis(100), control));
            }
        }
    }

    @Test
    void independentProcessesShareCapacityAndCrashReleasesTheSlot() throws Exception {
        Path ready = directory.resolve("ready");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path arguments = directory.resolve("java-arguments.txt");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-Xmx64m\n-XX:ActiveProcessorCount=1\n-XX:TieredStopAtLevel=1\n-cp\n" + quoted(classpath) + "\n" + Probe.class.getName() + "\n"
            + quoted(directory.toString()) + "\n" + quoted(ready.toString()) + "\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "@" + arguments).redirectErrorStream(true).start();
        try (var control = control(); var cancelled = control(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(Files.exists(ready), "独立执行进程没有取得共享名额");
            var capacity = new SandboxCapacity(1, directory.toString());
            assertEquals("SANDBOX_QUEUE_TIMEOUT", assertThrows(ApiException.class,
                () -> capacity.acquire(Duration.ofMillis(150), control)).code());
            var waiting = workers.submit(() -> {
                try (var permit = capacity.acquire(Duration.ofSeconds(5), cancelled)) {
                    fail("其他进程尚未释放共享名额");
                }
            });
            Thread.sleep(100);
            assertFalse(waiting.isDone());
            cancelled.close();
            assertInstanceOf(ExecutionStoppedException.class,
                assertThrows(ExecutionException.class, () -> waiting.get(2, TimeUnit.SECONDS)).getCause());
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            try (var permit = capacity.acquire(Duration.ofSeconds(3), control)) {
                var another = new SandboxCapacity(2, directory.toString());
                assertThrows(ApiException.class, () -> another.acquire(Duration.ofMillis(100), control));
            }
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aTimedOutWaiterDoesNotReleaseTheActiveProcessLock() throws Exception {
        SandboxLockProcessCheck.main(new String[]{directory.resolve("shared-lock").toString()});
    }

    @Test
    void failedSharedDirectoryDoesNotConsumeLocalCapacity() throws Exception {
        Path root = directory.resolve("shared");
        Files.writeString(root, "此处暂时不是目录");
        var capacity = new SandboxCapacity(1, root.toString());
        try (var control = control()) {
            assertThrows(ApiException.class, () -> capacity.acquire(Duration.ofSeconds(1), control));
            Files.delete(root);
            try (var permit = capacity.acquire(Duration.ofSeconds(1), control)) {
                assertTrue(permit.remaining().isPositive());
            }
        }
    }

    private static ToolCallControl control() {
        return new ToolCallControl(() -> {
        });
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"";
    }

    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            var capacity = new SandboxCapacity(1, arguments[0]);
            try (var control = control(); var permit = capacity.acquire(Duration.ofSeconds(10), control)) {
                Files.writeString(Path.of(arguments[1]), "已取得共享名额");
                Thread.sleep(30000);
            }
        }
    }
}
