package com.stonewu.agenteam.service.sandbox;

import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SandboxBackendNetworkMonitorTest {
    @Test
    void onlyAnActualNetworkChangeRestartsTheExecutorAndOnlyOnce() {
        var docker = mock(DockerWorkspaceCommands.class);
        var arguments = List.of("exec", "backend", "readlink", "/proc/self/ns/net");
        when(docker.run(arguments, Duration.ofSeconds(5))).thenReturn(
            new DockerWorkspaceCommands.Result(0, "net:[100]\n"),
            new DockerWorkspaceCommands.Result(1, "后端暂时未运行"),
            new DockerWorkspaceCommands.Result(0, "net:[200]\n"));
        var restarts = new AtomicInteger();
        try (var monitor = new SandboxBackendNetworkMonitor(docker, "backend", "net:[100]", restarts::incrementAndGet)) {
            monitor.poll();
            monitor.poll();
            assertEquals(0, restarts.get());
            monitor.poll();
            monitor.poll();
            assertEquals(1, restarts.get());
            verify(docker, times(3)).run(arguments, Duration.ofSeconds(5));
        }
    }
}
