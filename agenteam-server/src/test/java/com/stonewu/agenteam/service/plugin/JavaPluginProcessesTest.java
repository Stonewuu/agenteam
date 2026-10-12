package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginProcesses;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;
import com.stonewu.agenteam.support.JavaPluginFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JavaPluginProcessesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void discoversAndInvokesAnActualPf4jJarInAnotherProcess() throws Exception {
        byte[] artifact = JavaPluginFixture.build("1.0.0");
        var processes = new JavaPluginProcesses();
        var checked = new AtomicInteger();
        try (var control = new ToolCallControl(checked::incrementAndGet)) {
            var context = new ToolProviderContext("test-enterprise", Duration.ofSeconds(20), control);
            var discover = json.createObjectNode().put("mode", "discover");
            assertEquals("echo", processes.call("test", artifact, discover, context, () -> {
            }).get(0).path("name").asText());
            var invoke = json.createObjectNode().put("mode", "invoke").put("tool", "echo");
            invoke.putObject("arguments").put("value", "已执行");
            invoke.putObject("configuration").put("prefix", "测试");
            invoke.putObject("credentialHeaders");
            var result = processes.call("test", artifact, invoke, context, () -> {
            });
            assertEquals("测试已执行", result.path("content").asText());
            assertNotEquals(ProcessHandle.current().pid(), result.path("pid").asLong());
            assertTrue(checked.get() >= 1);
        }
    }

    @Test
    void invalidationTerminatesCodeThatIgnoresThreadInterruption() throws Exception {
        byte[] artifact = JavaPluginFixture.build("1.0.0");
        var processes = new JavaPluginProcesses();
        var active = new AtomicBoolean(true);
        var submitted = new AtomicBoolean();
        try (var control = new ToolCallControl(() -> submitted.set(true)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var context = new ToolProviderContext("test-enterprise", Duration.ofSeconds(20), control);
            var invoke = json.createObjectNode().put("mode", "invoke").put("tool", "hang");
            invoke.putObject("arguments");
            invoke.putObject("configuration");
            invoke.putObject("credentialHeaders");
            var pending = executor.submit(() -> processes.call("old", artifact, invoke, context, () -> {
                if (!active.get()) {
                    throw new ApiException(HttpStatus.CONFLICT, "JAVA_PLUGIN_UPGRADED", "插件已升级。");
                }
            }));
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!submitted.get() && System.nanoTime() < until) {
                Thread.sleep(20);
            }
            assertTrue(submitted.get());
            active.set(false);
            processes.stop("old");
            var failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals("JAVA_PLUGIN_UPGRADED", ((ApiException) failure.getCause()).code());
        }
    }

    @Test
    void recordsRemoteCauseAndSuppressedStackWithoutLeakingInputConfigurationOrCredentials() throws Exception {
        byte[] artifact = JavaPluginFixture.build("1.0.0");
        var logger = (Logger) LoggerFactory.getLogger(JavaPluginProcesses.class);
        var log = new ListAppender<ILoggingEvent>();
        log.start();
        logger.addAppender(log);
        try (var processes = new JavaPluginProcesses(); var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext("test-enterprise", Duration.ofSeconds(20), control);
            var request = json.createObjectNode().put("mode", "invoke").put("tool", "fail");
            request.putObject("arguments").put("value", "私密正文");
            request.putObject("configuration").put("prefix", "私密配置");
            request.putObject("credentialHeaders").put("Authorization", "Bearer private-credential");
            var failure = assertThrows(ApiException.class, () -> processes.call("failure-test", artifact, request,
                context, () -> {
                }));
            assertEquals("JAVA_PLUGIN_EXECUTION_FAILED", failure.code());
            String output = log.list.stream().map(event -> event.getFormattedMessage() +
                ThrowableProxyUtil.asString(event.getThrowableProxy())).reduce("", String::concat);
            assertTrue(output.contains("failure-test"));
            assertTrue(output.contains("Connection reset"));
            assertTrue(output.contains("Example.invoke"));
            assertTrue(output.contains("关闭连接失败"));
            assertFalse(output.contains("私密正文"));
            assertFalse(output.contains("私密配置"));
            assertFalse(output.contains("private-credential"));
        } finally {
            logger.detachAppender(log);
            log.stop();
        }
    }

    @Test
    void timeoutStopsAPluginThatNeverReturns() throws Exception {
        byte[] artifact = JavaPluginFixture.build("1.0.0");
        try (var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext("test-enterprise", Duration.ofSeconds(4), control);
            var request = json.createObjectNode().put("mode", "invoke").put("tool", "hang");
            request.putObject("arguments");
            request.putObject("configuration");
            request.putObject("credentialHeaders");
            var failure = assertThrows(ApiException.class, () -> new JavaPluginProcesses().call("timeout-test", artifact,
                request, context, () -> {
                }));
            assertEquals("JAVA_PLUGIN_TIMEOUT", failure.code());
        }
    }
}
