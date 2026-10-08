package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.controller.sandbox.ProjectSandboxServerController;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.*;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteProjectCommandExecutorTest {
    @TempDir
    Path root;

    @Test
    void usesRegisteredSharedFilesAuthenticatesAndCancelsWithoutReplacingTheProject() throws Exception {
        String token = "project-executor-test-token-with-at-least-32-characters";
        var json = new ObjectMapper();
        var settings = new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 200, 256, 1, 32, 30, "none");
        var runtime = new SandboxRuntimeSettings(32, 2, "", "");
        var layout = new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.resolve("shared").toString(), ""), json);
        var docker = new DockerWorkspaceCommands();
        String enterprise = "enterprise-" + root.getFileName();
        var project = new ProjectLocation(ProjectPaths.workspaceId(enterprise, "user"), "remote", enterprise, "user", "projects/remote");
        layout.prepareWorkspace(project.workspaceId(), false);
        for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
            Files.createDirectories(layout.project(project).resolve(area));
        }
        layout.register(project);
        Files.writeString(layout.project(project).resolve("keep.txt"), "保留的文件");
        var commands = new DockerProjectCommandExecutor(settings, runtime, layout, new UserContainerRuntime(settings, runtime, layout, docker), docker);
        try (var service = new SandboxExecutionService(new DockerWorkspaceRunner(settings, docker), settings, json, root.resolve("server"), 3);
             var pool = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(pool);
            server.createContext("/project-executions/", new ProjectSandboxServerController(service, commands, json, token));
            server.start();
            try {
                String origin = "http://127.0.0.1:" + server.getAddress().getPort();
                var remote = new RemoteProjectCommandExecutor(settings, json, origin, token);
                assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(origin + "/project-executions/" + UUID.randomUUID())).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
                try (var control = new ToolCallControl(() -> {
                })) {
                    var result = remote.execute(project, "first", "printf '远程结果' > result.txt", ".", Duration.ofSeconds(20), control);
                    assertEquals(0, result.exitCode());
                    assertEquals("远程结果", Files.readString(layout.project(project).resolve("result.txt")));
                    assertEquals(result, remote.execute(project, "first", "printf '远程结果' > result.txt", ".", Duration.ofSeconds(20), control));
                    assertEquals("保留的文件", Files.readString(layout.project(project).resolve("keep.txt")));
                    var unregistered = new ProjectLocation(project.workspaceId(), "missing", enterprise, "user", "projects/missing");
                    assertEquals("PROJECT_STORAGE_UNAVAILABLE", assertThrows(ApiException.class,
                        () -> remote.execute(unregistered, "missing", "touch nope", ".", Duration.ofSeconds(10), control)).code());
                }
                try (var control = new ToolCallControl(() -> {
                })) {
                    var task = pool.submit(() -> remote.execute(project, "cancel", "touch started.txt; sleep 40", ".", Duration.ofSeconds(25), control));
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    while (!Files.exists(layout.project(project).resolve("started.txt")) && System.nanoTime() < deadline) {
                        Thread.sleep(50);
                    }
                    assertTrue(Files.exists(layout.project(project).resolve("started.txt")));
                    control.close();
                    assertThrows(Exception.class, () -> task.get(10, TimeUnit.SECONDS));
                }
                try (var control = new ToolCallControl(() -> {
                })) {
                    remote.recover(project, Duration.ofSeconds(10), control);
                    assertEquals(0, remote.execute(project, "after-cancel", "test -f started.txt && test -f keep.txt", ".", Duration.ofSeconds(20), control).exitCode());
                }
            } finally {
                server.stop(0);
                docker.remove(UserContainerOwnership.name(layout.hostPath(layout.files(project.workspaceId()))));
            }
        }
    }
}
