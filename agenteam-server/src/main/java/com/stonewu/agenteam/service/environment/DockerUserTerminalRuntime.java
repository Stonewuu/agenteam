package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.environment.entity.EnvironmentTerminalState;
import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.project.UserContainerRuntime;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "docker", matchIfMissing = true)
public class DockerUserTerminalRuntime implements UserTerminalRuntime, AutoCloseable {
    private final EnvironmentTerminalStore store;
    private final EnvironmentTerminalWorker worker;
    private final UserSandboxRegistry registry;
    private final UserContainerRuntime containers;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public DockerUserTerminalRuntime(EnvironmentTerminalStore store, EnvironmentTerminalWorker worker,
            UserSandboxRegistry registry, UserContainerRuntime containers, Clock clock) {
        this.store = store;
        this.worker = worker;
        this.registry = registry;
        this.containers = containers;
        this.clock = clock;
    }

    @Override
    public EnvironmentTerminalView exchange(ProjectLocation project, EnvironmentTerminalInput input) {
        InputValidation.validate(input);
        var created = new AtomicBoolean();
        var view = store.locked(project, previous -> {
            var state = previous;
            if (state != null && state.active() && clock.millis() - state.workerAt >= 60000) {
                state.status = "error";
                state.error = "终端连接已中断，请重新连接。";
                state.busy = false;
                state.pendingInput = "";
                store.save(project, state);
            }
            if ("open".equals(input.action()) && (state == null || !state.active() && !state.id.equals(input.id()))) {
                var container = registry.state(project.workspaceId());
                if (container == null || !"running".equals(container.status())
                    || !container.daemonId().equals(containers.daemon(Duration.ofSeconds(10), null))
                    || !container.containerId().equals(containers.ownedId(project, Duration.ofSeconds(10)))
                    || !containers.running(container.containerId(), Duration.ofSeconds(10))) {
                    throw new ApiException(HttpStatus.CONFLICT, "ENVIRONMENT_DISCONNECTED", "请先连接环境，再打开终端。");
                }
                state = new EnvironmentTerminalState();
                state.id = input.id();
                state.status = "starting";
                state.containerId = container.containerId();
                state.generation = container.generation();
                state.clientAt = state.workerAt = clock.millis();
                state.cols = input.cols();
                state.rows = input.rows();
                store.resetOutput(project);
                store.save(project, state);
                registry.markActivity(project.workspaceId(), clock.millis());
                created.set(true);
            } else if (!"open".equals(input.action())) {
                store.require(state, input.id());
            }
            if (state == null) {
                throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_DISCONNECTED", "请重新连接终端。");
            }
            if (state.active()) {
                update(project, state, input);
            }
            if (created.get()) {
                var initial = state;
                workers.submit(() -> worker.run(project, initial, shutdown));
            }
            return store.view(project, state, input.cursor());
        });
        if ("poll".equals(input.action()) && view.end() <= input.cursor() && "running".equals(view.status())) {
            long deadline = System.nanoTime() + Duration.ofMillis(750).toNanos();
            do {
                try {
                    Thread.sleep(75);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    break;
                }
                view = store.locked(project, state -> store.view(project, store.require(state, input.id()), input.cursor()));
            } while (view.end() <= input.cursor() && "running".equals(view.status()) && System.nanoTime() < deadline);
        }
        return view;
    }

    private void update(ProjectLocation project, EnvironmentTerminalState state, EnvironmentTerminalInput input) throws IOException {
        boolean changed = clock.millis() - state.clientAt >= 10000;
        if (changed) {
            state.clientAt = clock.millis();
        }
        if ("input".equals(input.action())) {
            String data = input.data() == null ? "" : input.data();
            String hash = Utf8Text.revision(state.id, data);
            if (input.sequence() == state.sequence && hash.equals(state.inputHash)) {
                return;
            }
            if (input.sequence() != state.sequence + 1) {
                throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_INPUT_CONFLICT", "终端已在其他窗口操作，请重新连接。");
            }
            if (Utf8Text.size(state.pendingInput + data) > 65536) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TERMINAL_INPUT_BUSY", "终端正在处理输入，请稍后再试。");
            }
            state.sequence = input.sequence();
            state.inputHash = hash;
            state.pendingInput += data;
            // 键盘输入和粘贴才算活动，输出轮询与窗口尺寸变化不延长空闲时间。
            registry.markActivity(project.workspaceId(), clock.millis());
            changed = true;
        } else if ("resize".equals(input.action())) {
            state.cols = input.cols();
            state.rows = input.rows();
            changed = true;
        } else if ("close".equals(input.action())) {
            state.status = "closing";
            changed = true;
        }
        if (changed) {
            store.save(project, state);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        shutdown.set(true);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
