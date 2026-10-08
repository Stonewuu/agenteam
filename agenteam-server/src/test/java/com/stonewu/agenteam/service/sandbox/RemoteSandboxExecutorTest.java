package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.controller.sandbox.SandboxServerController;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSandboxExecutorTest {
    @TempDir
    Path directory;

    @Test
    void transfersFilesBetweenDifferentRootsAndAuthenticatesEveryRequest() throws Exception {
        String token = "test-sandbox-access-token-with-32-characters";
        var settings = new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 32, 2, 100, 256, 1, 32, 30, "none");
        var docker = new DockerWorkspaceRunner(settings, new DockerWorkspaceCommands(), new SandboxRuntimeSettings(64, 1, "", ""));
        try (var service = new SandboxExecutionService(docker, settings, new ObjectMapper(), directory.resolve("server"), 2);
             var threads = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newHttpClient()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(threads);
            server.createContext("/executions/", new SandboxServerController(service, token));
            server.start();
            try {
                String origin = "http://127.0.0.1:" + server.getAddress().getPort();
                var remote = new RemoteSandboxExecutor(settings, new ObjectMapper(), origin, token);
                Path clientRoot = Files.createDirectory(directory.resolve("client"));
                Path source = clientRoot.resolve("source");
                for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
                    Files.createDirectories(source.resolve(area));
                }
                Files.writeString(source.resolve("inputs/原件.txt"), "不可修改的原件");
                try (var control = new ToolCallControl(() -> {
                })) {
                    var result = remote.execute(source, clientRoot.resolve("result"), clientRoot, "remote-test",
                        "cat /workspace/inputs/原件.txt > /workspace/outputs/结果.txt", "work", Duration.ofSeconds(20), control);
                    assertEquals(0, result.exitCode());
                    assertEquals("不可修改的原件", Files.readString(clientRoot.resolve("result/outputs/结果.txt")));
                    assertFalse(result.stdoutPath().contains(directory.toString()));
                }
                String cancelled = UUID.randomUUID().toString();
                var address = URI.create(origin + "/executions/" + cancelled);
                assertEquals(401, client.send(HttpRequest.newBuilder(address).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(204, client.send(HttpRequest.newBuilder(address).header("Authorization", "Bearer " + token).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals("SANDBOX_CANCELLED", assertThrows(ApiException.class, () -> service.execute(cancelled, InputStream.nullInputStream())).code());
                try (var control = new ToolCallControl(() -> {
                })) {
                    var task = threads.submit(() -> remote.execute(source, clientRoot.resolve("cancelled"), clientRoot, "remote-cancel",
                        "sleep 60", "work", Duration.ofSeconds(25), control));
                    Thread.sleep(1500);
                    control.close();
                    assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS));
                    assertFalse(Files.exists(clientRoot.resolve("cancelled/outputs")));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    try (var entries = Files.list(directory.resolve("server"))) {
                        if (entries.noneMatch(Files::isDirectory)) {
                            break;
                        }
                    }
                    Thread.sleep(100);
                }
                try (var entries = Files.list(directory.resolve("server"))) {
                    assertTrue(entries.noneMatch(Files::isDirectory));
                }
                try (var control = new ToolCallControl(() -> {
                })) {
                    var task = threads.submit(() -> remote.execute(source, clientRoot.resolve("interrupted"), clientRoot, "remote-interrupt",
                        "sleep 60", "work", Duration.ofSeconds(25), control));
                    long startedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean started = false;
                    while (System.nanoTime() < startedDeadline) {
                        try (var entries = Files.list(directory.resolve("server"))) {
                            started = entries.anyMatch(Files::isDirectory);
                        }
                        if (started) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertTrue(started, "独立节点应已接收任务");
                    task.cancel(true);
                    long cancelledDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean empty = false;
                    while (System.nanoTime() < cancelledDeadline) {
                        try (var entries = Files.list(directory.resolve("server"))) {
                            empty = entries.noneMatch(Files::isDirectory);
                        }
                        if (empty) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertTrue(empty, "调用线程中断后应通知独立节点停止，不能依赖稍后关闭控制对象");
                }
            } finally {
                server.stop(0);
            }
        }
    }
}
