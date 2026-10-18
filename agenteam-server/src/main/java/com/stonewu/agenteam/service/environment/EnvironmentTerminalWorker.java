package com.stonewu.agenteam.service.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.environment.entity.EnvironmentTerminalState;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ExceptionDiagnostics;
import com.stonewu.agenteam.service.project.ProjectCapacityMonitor;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.UserSandboxRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个终端对应一个容器内管理进程；没有浏览器续期时关闭输入并清理后代进程。 */
@Component
public class EnvironmentTerminalWorker {
    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentTerminalWorker.class);
    private final EnvironmentTerminalStore store;
    private final EnvironmentTerminalProcess processes;
    private final ProjectWorkspaceLayout layout;
    private final UserSandboxRegistry registry;
    private final WorkspaceSettings settings;
    private final ObjectMapper json;
    private final Clock clock;

    public EnvironmentTerminalWorker(EnvironmentTerminalStore store, EnvironmentTerminalProcess processes,
            ProjectWorkspaceLayout layout, UserSandboxRegistry registry, WorkspaceSettings settings, ObjectMapper json, Clock clock) {
        this.store = store;
        this.processes = processes;
        this.layout = layout;
        this.registry = registry;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    public void run(ProjectLocation project, EnvironmentTerminalState initial, AtomicBoolean shutdown) {
        Process process = null;
        Thread reader = null;
        String error = null;
        var stopped = new AtomicBoolean();
        try {
            var monitor = new ProjectCapacityMonitor(layout.files(project.workspaceId()), settings, stopped);
            var baseline = monitor.usage();
            process = processes.start(project, initial);
            var currentProcess = process;
            reader = Thread.ofVirtual().start(() -> read(project, initial.id, currentProcess, stopped));
            Thread.ofVirtual().start(() -> {
                try (var input = currentProcess.getErrorStream()) {
                    input.transferTo(OutputStream.nullOutputStream());
                } catch (IOException failure) {
                    LOG.warn("终端诊断流读取失败，工作空间编号 {}，终端编号 {}", project.workspaceId(), initial.id, failure);
                }
            });
            int cols = initial.cols;
            int rows = initial.rows;
            long checked = 0;
            while (process.isAlive() && !stopped.get() && !shutdown.get()) {
                var step = store.locked(project, state -> {
                    store.require(state, initial.id);
                    if (clock.millis() - state.clientAt >= 60000 || "closing".equals(state.status)) {
                        return Map.<String, Object>of("close", true);
                    }
                    var container = registry.state(project.workspaceId());
                    if (container == null || !state.generation.equals(container.generation()) || !"running".equals(container.status())) {
                        return Map.<String, Object>of("close", true);
                    }
                    var message = Map.<String, Object>of("data", state.pendingInput, "cols", state.cols, "rows", state.rows);
                    if (!state.pendingInput.isEmpty() || clock.millis() - state.workerAt > 1000 || "starting".equals(state.status)) {
                        state.pendingInput = "";
                        state.workerAt = clock.millis();
                        state.status = "running";
                        store.save(project, state);
                    }
                    return message;
                });
                if (step.containsKey("close")) {
                    break;
                }
                int nextCols = (int) step.get("cols");
                int nextRows = (int) step.get("rows");
                if (!((String) step.get("data")).isEmpty() || cols != nextCols || rows != nextRows) {
                    process.getOutputStream().write(json.writeValueAsBytes(step));
                    process.getOutputStream().write('\n');
                    process.getOutputStream().flush();
                    cols = nextCols;
                    rows = nextRows;
                }
                if (clock.millis() - checked >= 1500) {
                    var usage = monitor.usage();
                    if (monitor.exceeded(usage) && usage.increasedFrom(baseline)) {
                        error = "环境文件已达到容量限制，请清理后继续。";
                        break;
                    }
                    checked = clock.millis();
                }
                Thread.sleep(30);
            }
        } catch (Exception failure) {
            LOG.warn("终端连接结束，工作空间编号 {}，终端编号 {}", project.workspaceId(), initial.id, ExceptionDiagnostics.withoutMessages(failure));
            error = "终端连接已中断，请重新连接。";
        } finally {
            stopped.set(true);
            if (process != null) {
                try {
                    process.getOutputStream().close();
                    if (!process.waitFor(7, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        error = "终端连接已中断，请重新连接。";
                    } else if (process.exitValue() != 0 && error == null) {
                        LOG.warn("终端管理进程退出，工作空间编号 {}，终端编号 {}，退出码 {}", project.workspaceId(), initial.id, process.exitValue());
                        error = "终端连接已中断，请重新连接。";
                    }
                    if (reader != null) {
                        reader.join(1000);
                    }
                } catch (IOException | InterruptedException failure) {
                    LOG.warn("关闭终端连接失败，工作空间编号 {}，终端编号 {}", project.workspaceId(), initial.id, failure);
                    process.destroyForcibly();
                    error = "终端连接已中断，请重新连接。";
                }
            }
            finish(project, initial.id, error);
        }
    }

    private void read(ProjectLocation project, String id, Process process, AtomicBoolean stopped) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readFrame(reader)) != null) {
                var frame = json.readTree(line);
                store.locked(project, state -> {
                    store.require(state, id);
                    if (frame.has("output")) {
                        store.append(project, state, Base64.getDecoder().decode(frame.path("output").asText()));
                    }
                    if (frame.has("busy")) {
                        boolean busy = frame.path("busy").asBoolean();
                        if (state.busy && !busy) {
                            registry.markActivity(project.workspaceId(), clock.millis());
                            layout.recordFileChange(project.workspaceId());
                        }
                        state.busy = busy;
                        state.directory = frame.path("directory").asText("/workspace");
                    }
                    store.save(project, state);
                    return null;
                });
            }
        } catch (Exception failure) {
            LOG.warn("终端输出读取失败，工作空间编号 {}，终端编号 {}", project.workspaceId(), id, ExceptionDiagnostics.withoutMessages(failure));
            stopped.set(true);
        }
    }

    private String readFrame(BufferedReader reader) throws IOException {
        var line = new StringBuilder();
        int value;
        while ((value = reader.read()) >= 0) {
            if (value == '\n') {
                return line.toString();
            }
            if (line.length() >= 131072) {
                throw new IOException("终端数据超过允许范围");
            }
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void finish(ProjectLocation project, String id, String error) {
        try {
            store.locked(project, state -> {
                if (state != null && state.id.equals(id)) {
                    state.status = error == null ? "closed" : "error";
                    state.error = error;
                    state.busy = false;
                    state.pendingInput = "";
                    store.save(project, state);
                    registry.markActivity(project.workspaceId(), clock.millis());
                    layout.recordFileChange(project.workspaceId());
                }
                return null;
            });
        } catch (RuntimeException failure) {
            LOG.warn("保存终端结束状态失败，工作空间编号 {}，终端编号 {}", project.workspaceId(), id, failure);
        }
    }
}
