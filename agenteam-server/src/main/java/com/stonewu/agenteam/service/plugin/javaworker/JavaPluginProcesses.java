package com.stonewu.agenteam.service.plugin.javaworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.tool.api.ToolContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;

/**
 * 每次调用使用独立进程，失效或超时后直接结束该进程及其子进程。
 */
@Component
public class JavaPluginProcesses implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JavaPluginProcesses.class);
    private final Map<String, Set<Process>> running = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(4);

    public JsonNode call(String installation, byte[] artifact, ObjectNode request, ToolContext context,
                         Runnable requireAvailable) {
        if (!capacity.tryAcquire()) {
            throw unavailable("JAVA_PLUGIN_BUSY", "插件执行数量已达上限，请稍后重试。", null);
        }
        Path directory = null;
        Process process = null;
        long deadline = System.nanoTime() + context.timeout().toNanos();
        try {
            requireAvailable.run();
            context.cancellation().requireActive();
            directory = Files.createTempDirectory("agenteam-java-plugin-");
            Path file = directory.resolve("plugin.jar");
            Files.write(file, artifact);
            var command = JavaPluginCommand.command(file);
            Path argumentsFile = directory.resolve("java.args");
            var arguments = new ArrayList<String>();
            for (String argument : command.subList(1, command.size())) {
                arguments.add("\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
            Files.write(argumentsFile, arguments);
            var builder = new ProcessBuilder(List.of(command.getFirst(), "@" + argumentsFile));
            builder.directory(directory.toFile());
            // 只保留启动系统程序必需的环境，不向插件传递数据库和平台密钥。
            builder.environment().keySet().removeIf(key -> !Set.of("SystemRoot", "WINDIR", "TEMP", "TMP", "LANG")
                .contains(key));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            Process active = process;
            running.compute(installation, (key, existing) -> {
                var processes = existing == null ? ConcurrentHashMap.<Process>newKeySet() : existing;
                processes.add(active);
                return processes;
            });
            context.cancellation().track(() -> terminate(active));
            requireAvailable.run();
            var protocol = new JavaPluginProtocol();
            request.put("timeoutMillis", context.timeout().toMillis());
            protocol.write(active.getOutputStream(), request);
            var reader = Executors.newVirtualThreadPerTaskExecutor();
            try {
                while (true) {
                    var incoming = reader.submit(() -> protocol.read(active.getInputStream()));
                    JsonNode message;
                    while (true) {
                        requireAvailable.run();
                        context.cancellation().requireActive();
                        if (System.nanoTime() >= deadline) {
                            throw unavailable("JAVA_PLUGIN_TIMEOUT", "插件执行超时，已停止本次调用。", null);
                        }
                        try {
                            message = incoming.get(200, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException waiting) {
                            // 每次等待后重新核对数据库状态，其他服务实例卸载也能终止本机调用。
                        }
                    }
                    requireAvailable.run();
                    if (message.path("kind").asText().equals("beforeSend")) {
                        context.beforeSend();
                        requireAvailable.run();
                        protocol.write(active.getOutputStream(), Map.of("approved", true));
                    } else if (message.path("kind").asText().equals("result")) {
                        return message.path("value");
                    } else {
                        var cause = JavaPluginFailures.safe(JavaPluginFailures.decode(message.path("error")), request);
                        throw unavailable("JAVA_PLUGIN_EXECUTION_FAILED", "插件执行失败，请联系插件维护者。", cause);
                    }
                }
            } finally {
                terminate(active);
                reader.shutdownNow();
            }
        } catch (ExecutionStoppedException failure) {
            throw failure;
        } catch (ApiException failure) {
            if (failure.getCause() != null) {
                LOG.warn("扩展插件调用失败，安装编号 {}，任务编号 {}，操作编号 {}", installation,
                    context.runId(), context.operationId(), failure);
            }
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable("JAVA_PLUGIN_CANCELLED", "插件调用已取消。", failure);
        } catch (ExecutionException failure) {
            requireAvailable.run();
            var safe = JavaPluginFailures.safe(failure.getCause(), request);
            LOG.warn("扩展插件进程退出，安装编号 {}，任务编号 {}", installation, context.runId(), safe);
            throw unavailable("JAVA_PLUGIN_PROCESS_FAILED", "插件进程已退出，本次操作结果无法确认。", safe);
        } catch (Exception failure) {
            var safe = JavaPluginFailures.safe(failure, request);
            LOG.warn("扩展插件进程调用失败，安装编号 {}，任务编号 {}", installation, context.runId(), safe);
            throw unavailable("JAVA_PLUGIN_PROCESS_FAILED", "插件进程未能完成本次调用。", safe);
        } finally {
            if (process != null) {
                terminate(process);
                Process finished = process;
                running.computeIfPresent(installation, (key, processes) -> {
                    processes.remove(finished);
                    return processes.isEmpty() ? null : processes;
                });
            }
            if (directory != null) {
                try {
                    // 目录由本次调用创建；不跟随符号链接，连同插件创建的临时文件一起释放。
                    try (var files = Files.walk(directory)) {
                        for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                } catch (Exception failure) {
                    LOG.warn("插件临时文件清理失败，安装编号 {}", installation, failure);
                }
            }
            capacity.release();
        }
    }

    public void stop(String installation) {
        running.getOrDefault(installation, Set.of()).forEach(JavaPluginProcesses::terminate);
    }

    @PreDestroy
    @Override
    public void close() {
        running.values().forEach(processes -> processes.forEach(JavaPluginProcesses::terminate));
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static ApiException unavailable(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.CONFLICT, code, message, cause);
    }
}
