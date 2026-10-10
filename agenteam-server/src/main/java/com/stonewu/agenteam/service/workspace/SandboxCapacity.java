package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 等待运行位置也计入调用时间，取消后不再启动新的容器。
 */
@Component
public final class SandboxCapacity {
    private final Semaphore slots;
    private final Path sharedRoot;

    public SandboxCapacity(int maximum) {
        this(maximum, "");
    }

    public SandboxCapacity(int maximum, String capacityRoot) {
        slots = new Semaphore(maximum, true);
        sharedRoot = capacityRoot.isBlank() ? null : Path.of(capacityRoot).toAbsolutePath().normalize();
    }

    @Autowired
    public SandboxCapacity(SandboxRuntimeSettings runtime) {
        this(runtime.maximumConcurrent(), runtime.capacityRoot());
    }

    public Permit acquire(Duration timeout, ToolCallControl control) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            control.requireActive();
            if (System.nanoTime() >= deadline) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_QUEUE_TIMEOUT",
                    "执行环境仍在处理其他任务，请稍后重试。");
            }
            if (slots.tryAcquire()) {
                try {
                    return new Permit(deadline, sharedRoot == null ? null
                        : SandboxHostPermit.acquire(sharedRoot, deadline, control));
                } catch (IOException failure) {
                    slots.release();
                    throw WorkspacePaths.io(failure);
                } catch (RuntimeException failure) {
                    slots.release();
                    throw failure;
                }
            }
            control.pause(Duration.ofMillis(50));
        }
    }

    public final class Permit implements AutoCloseable {
        private final long deadline;
        private final SandboxHostPermit shared;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(long deadline, SandboxHostPermit shared) {
            this.deadline = deadline;
            this.shared = shared;
        }

        public Duration remaining() {
            return DockerWorkspaceCommands.remaining(deadline);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try {
                    if (shared != null) {
                        shared.close();
                    }
                } catch (IOException failure) {
                    throw WorkspacePaths.io(failure);
                } finally {
                    slots.release();
                }
            }
        }
    }
}
