package com.stonewu.agenteam.service.plugin;

import java.util.concurrent.CountDownLatch;
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

import static org.junit.jupiter.api.Assertions.*;

class JavaPluginProcessesTest {
    private final ObjectMapper json = new ObjectMapper();



    @Test
    void invalidationTerminatesCodeThatIgnoresThreadInterruption() throws Exception {
        byte[] artifact = JavaPluginFixture.build("1.0.0");
        var active = new AtomicBoolean(true);
        var submitted = new CountDownLatch(1);
        try (var processes = new JavaPluginProcesses(); var control = new ToolCallControl(submitted::countDown); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
            assertTrue(submitted.await(10, TimeUnit.SECONDS));
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
        try (var processes = new JavaPluginProcesses(); var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext("test-enterprise", Duration.ofSeconds(4), control);
            var request = json.createObjectNode().put("mode", "invoke").put("tool", "hang");
            request.putObject("arguments");
            request.putObject("configuration");
            request.putObject("credentialHeaders");
            var failure = assertThrows(ApiException.class, () -> processes.call("timeout-test", artifact,
                request, context, () -> {
                }));
            assertEquals("JAVA_PLUGIN_TIMEOUT", failure.code());
        }
    }
}
