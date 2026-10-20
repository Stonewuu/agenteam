package com.stonewu.agenteam.service.sandbox;

import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 后端自动重启改变网络空间后，退出当前执行服务，由容器重启策略重新连接。 */
public final class SandboxBackendNetworkMonitor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SandboxBackendNetworkMonitor.class);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);
    private final DockerWorkspaceCommands docker;
    private final String backend;
    private final String namespace;
    private final Runnable restart;
    private final AtomicBoolean restarting = new AtomicBoolean();
    private ScheduledExecutorService timer;

    SandboxBackendNetworkMonitor(DockerWorkspaceCommands docker, String backend, String namespace, Runnable restart) {
        this.docker = docker;
        this.backend = backend;
        this.namespace = namespace;
        this.restart = restart;
    }

    public static SandboxBackendNetworkMonitor start(DockerWorkspaceCommands docker, String container) throws IOException {
        if (container == null || container.isBlank()) {
            return null;
        }
        if (!container.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("执行服务容器名称无效。");
        }
        String mode = docker.require(List.of("inspect", "--type=container", "--format={{.HostConfig.NetworkMode}}", container),
            CHECK_TIMEOUT, null).output().trim();
        if (!mode.startsWith("container:")) {
            return null;
        }
        String backend = mode.substring("container:".length());
        if (!backend.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalStateException("Docker 返回了无效的后端容器标识。");
        }
        String namespace = Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString();
        var monitor = new SandboxBackendNetworkMonitor(docker, backend, namespace, () -> System.exit(0));
        monitor.timer = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "sandbox-backend-network");
            thread.setDaemon(true);
            return thread;
        });
        monitor.timer.scheduleWithFixedDelay(monitor::poll, 5, 10, TimeUnit.SECONDS);
        return monitor;
    }

    void poll() {
        if (restarting.get()) {
            return;
        }
        try {
            var result = docker.run(List.of("exec", backend, "readlink", "/proc/self/ns/net"), CHECK_TIMEOUT);
            if (result.exitCode() != 0) {
                // 后端尚未启动时继续等待，不因暂时不可访问而反复重启执行服务。
                return;
            }
            String current = result.output().trim();
            if (!current.matches("net:\\[\\d+\\]")) {
                throw new IllegalStateException("后端网络空间检查没有返回有效标识。");
            }
            if (!namespace.equals(current) && restarting.compareAndSet(false, true)) {
                LOG.warn("后端网络空间已变化，执行服务即将退出，由容器重启策略重新连接。");
                restart.run();
            }
        } catch (RuntimeException failure) {
            LOG.warn("后端网络空间检查失败，下次继续检查", failure);
        }
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
