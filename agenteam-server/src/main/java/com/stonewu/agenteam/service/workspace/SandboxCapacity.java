package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * 等待运行位置也计入调用时间，取消后不再启动新的容器。
 */
@Component
public final class SandboxCapacity {
    private final Semaphore slots;

    public SandboxCapacity(int maximum) {
        slots = new Semaphore(maximum, true);
    }

    @Autowired
    public SandboxCapacity(SandboxRuntimeSettings runtime) {
        this(runtime.maximumConcurrent());
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
                return new Permit(deadline);
            }
            control.pause(Duration.ofMillis(50));
        }
    }

    public final class Permit implements AutoCloseable {
        private final long deadline;
        private boolean released;

        private Permit(long deadline) {
            this.deadline = deadline;
        }

        public Duration remaining() {
            return DockerWorkspaceCommands.remaining(deadline);
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                slots.release();
            }
        }
    }
}
