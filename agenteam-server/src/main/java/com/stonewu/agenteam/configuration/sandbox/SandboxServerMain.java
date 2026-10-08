package com.stonewu.agenteam.configuration.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxLifecycleSettings;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.controller.sandbox.ProjectSandboxServerController;
import com.stonewu.agenteam.controller.sandbox.SandboxContainerCleanupController;
import com.stonewu.agenteam.controller.sandbox.SandboxServerController;
import com.stonewu.agenteam.service.project.*;
import com.stonewu.agenteam.service.sandbox.SandboxExecutionService;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceRunner;
import com.stonewu.agenteam.service.workspace.SandboxCapacity;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 独立执行进程不启动 Spring，不连接数据库或加载业务密钥。
 */
public final class SandboxServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxServerMain.class);

    private SandboxServerMain() {
    }

    public static void main(String[] args) {
        try {
            String token = value("AGENTEAM_SANDBOX_REMOTE_TOKEN", "");
            if (token.length() < 32) {
                throw new IllegalArgumentException("独立执行服务需要至少三十二字符的访问凭据");
            }
            Path root = Path.of(value("AGENTEAM_SANDBOX_SERVER_ROOT", ".agenteam/sandbox-server")).toAbsolutePath()
                .normalize();
            var settings = new WorkspaceSettings(root.toString(),
                value("AGENTEAM_SANDBOX_IMAGE", "agenteam/sandbox-office:local"), true,
                number("AGENTEAM_SANDBOX_WORKSPACE_MB", 512), number("AGENTEAM_SANDBOX_FILE_MB", 20),
                number("AGENTEAM_SANDBOX_MAXIMUM_FILES", 2000), number("AGENTEAM_SANDBOX_MEMORY_MB", 2048),
                number("AGENTEAM_SANDBOX_CPUS", 1), number("AGENTEAM_SANDBOX_PROCESS_LIMIT", 64),
                number("AGENTEAM_SANDBOX_TIMEOUT_SECONDS", 1800), value("AGENTEAM_SANDBOX_NETWORK", "none"));
            var runtime = new SandboxRuntimeSettings(number("AGENTEAM_SANDBOX_TEMPORARY_MB", 256),
                number("AGENTEAM_SANDBOX_MAXIMUM_CONCURRENT", 2), value("AGENTEAM_SANDBOX_LOCAL_MOUNT_ROOT", ""),
                value("AGENTEAM_SANDBOX_HOST_MOUNT_ROOT", ""));
            var userSettings = new UserWorkspaceSettings(
                value("AGENTEAM_USER_WORKSPACES_ROOT", root.resolve("user-workspaces").toString()),
                value("AGENTEAM_USER_WORKSPACES_HOST_ROOT", ""));
            var docker = new DockerWorkspaceCommands(userSettings);
            docker.require(List.of("image", "inspect", settings.image()));
            var capacity = new SandboxCapacity(runtime.maximumConcurrent());
            var executions = new SandboxExecutionService(new DockerWorkspaceRunner(settings, docker, runtime, capacity),
                settings, new ObjectMapper(), root, runtime.maximumConcurrent() * 2);
            var json = new ObjectMapper();
            var layout = new ProjectWorkspaceLayout(userSettings, json);
            var idleSettings = new SandboxLifecycleSettings(number("AGENTEAM_SANDBOX_IDLE_SECONDS", 900),
                number("AGENTEAM_SANDBOX_IDLE_SCAN_SECONDS", 30), number("AGENTEAM_SANDBOX_USAGE_STALE_SECONDS", 60),
                number("AGENTEAM_SANDBOX_STOP_SECONDS", 10), number("AGENTEAM_SANDBOX_MAXIMUM_RUNNING_CONTAINERS", 4));
            var containers = new UserContainerRuntime(settings, runtime, layout, docker, idleSettings);
            var commands = new ProjectCommandProcess(layout, docker, json);
            var lifecycle = new UserSandboxLifecycle(layout, new UserSandboxRegistry(layout), containers, commands,
                idleSettings, Clock.systemUTC());
            var projects = new DockerProjectCommandExecutor(settings, layout, containers, capacity, commands,
                lifecycle);
            var idleCheck = Executors.newSingleThreadScheduledExecutor();
            idleCheck.scheduleWithFixedDelay(lifecycle::poll, idleSettings.scanSeconds(), idleSettings.scanSeconds(),
                TimeUnit.SECONDS);
            var server = HttpServer.create(new InetSocketAddress(value("AGENTEAM_SANDBOX_BIND_ADDRESS", "127.0.0.1"),
                number("AGENTEAM_SANDBOX_PORT", 8092)), 16);
            var workers = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(workers);
            server.createContext("/executions/", new SandboxServerController(executions, token));
            server.createContext("/project-executions/",
                new ProjectSandboxServerController(executions, projects, json, token));
            server.createContext("/container-cleanup/", new SandboxContainerCleanupController(docker, json, token));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                idleCheck.shutdownNow();
                executions.close();
                server.stop(1);
                workers.shutdownNow();
            }, "独立执行服务关闭"));
            server.start();
            LOG.info("独立执行服务已启动，端口 {}，工作目录 {}", server.getAddress().getPort(), root);
        } catch (Exception failure) {
            LOG.error("独立执行服务启动失败", failure);
            System.exit(1);
        }
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static int number(String name, int fallback) {
        return Integer.parseInt(value(name, String.valueOf(fallback)));
    }
}
