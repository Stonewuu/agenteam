package com.stonewu.agenteam.service.sandbox;

import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.environment.UserTerminalRuntime;
import com.stonewu.agenteam.service.environment.EnvironmentTerminalStore;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 复用远程执行测试的同一个容器，验证真实终端而不重复启动应用。 */
final class TerminalInteractionAssertions {
    private final UserTerminalRuntime runtime;
    private final ProjectLocation project;
    private final StringBuilder output = new StringBuilder();
    private EnvironmentTerminalView view;
    private long sequence;
    private long cursor;

    private TerminalInteractionAssertions(UserTerminalRuntime runtime, ProjectLocation project) {
        this.runtime = runtime;
        this.project = project;
        view = runtime.exchange(project, request("open", UUID.randomUUID().toString(), "", 0, 0));
    }

    static void verify(UserTerminalRuntime runtime, ProjectLocation project, ProjectWorkspaceLayout layout,
            EnvironmentTerminalStore store, TerminalClock clock) throws Exception {
        var test = new TerminalInteractionAssertions(runtime, project);
        test.await(view -> "running".equals(view.status()));
        String first = "export ENV_PROBE=remembered; cd /workspace/projects/remote; printf x >> terminal.txt\r";
        var input = request("input", test.view.id(), first, ++test.sequence, test.cursor);
        runtime.exchange(project, input);
        runtime.exchange(project, input);
        test.send("printf '%s:%s' \"$ENV_PROBE\" \"$PWD\" > terminal-state.txt; stty size > terminal-size.txt\r");
        test.await(view -> Files.exists(layout.project(project).resolve("terminal-size.txt")) && !view.busy()
            && "/workspace/projects/remote".equals(view.directory()));
        assertEquals("x", Files.readString(layout.project(project).resolve("terminal.txt")), "重复请求不会重复输入");
        assertEquals("remembered:/workspace/projects/remote", Files.readString(layout.project(project).resolve("terminal-state.txt")));
        assertEquals("33 101", Files.readString(layout.project(project).resolve("terminal-size.txt")).trim());
        assertEquals("/workspace/projects/remote", test.view.directory());
        test.send("python3 -c \"v=input('value>');print('VALUE:'+v)\"\r");
        test.await(view -> view.busy() && test.output.toString().contains("value>"));
        test.send("中文交互\r");
        test.await(view -> test.output.toString().contains("VALUE:中文交互") && !view.busy());
        test.send("sleep 30\r");
        test.await(EnvironmentTerminalView::busy);
        test.send("\u0003");
        test.await(view -> !view.busy());
        test.send("while :; do :; done\r");
        test.await(EnvironmentTerminalView::busy);
        test.send("\u0003");
        test.await(view -> !view.busy());
        String id = test.view.id();
        var resized = new EnvironmentTerminalInput("resize", id, "", test.sequence, test.cursor, 89, 27);
        runtime.exchange(project, resized);
        test.send("stty size > terminal-resized.txt; printf '\\033[31mANSI_OK\\033[0m\\n'\r");
        test.await(view -> Files.exists(layout.project(project).resolve("terminal-resized.txt")) && test.output.toString().contains("\u001b[31mANSI_OK"));
        assertEquals("27 89", Files.readString(layout.project(project).resolve("terminal-resized.txt")).trim());
        runtime.exchange(project, request("close", id, "", test.sequence, test.cursor));
        test.await(view -> "closed".equals(view.status()));
        var other = new ProjectLocation(project.workspaceId(), "missing", project.enterpriseId(), project.userId(), "projects/missing");
        assertThrows(ApiException.class, () -> runtime.exchange(other, request("poll", id, "", 0, 0)));
        var disconnected = new TerminalInteractionAssertions(runtime, project);
        disconnected.await(view -> "running".equals(view.status()));
        disconnected.send("sleep 120 &\r");
        disconnected.await(EnvironmentTerminalView::busy);
        clock.offset.addAndGet(61000);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (store.read(project).active() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("closed", store.read(project).status, "浏览器失联后关闭终端，并清理后台子进程");
        assertEquals("x", Files.readString(layout.project(project).resolve("terminal.txt")));
    }

    private void send(String data) {
        runtime.exchange(project, request("input", view.id(), data, ++sequence, cursor));
    }

    private void await(Predicate<EnvironmentTerminalView> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            view = runtime.exchange(project, request("poll", view.id(), "", sequence, cursor));
            output.append(new String(Base64.getDecoder().decode(view.output()), StandardCharsets.UTF_8));
            cursor = view.end();
            if (condition.test(view)) {
                return;
            }
            assertNotEquals("error", view.status(), view.error());
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("终端未达到预期状态，当前状态 " + view.status() + "，输出 " + output);
    }

    private static EnvironmentTerminalInput request(String action, String id, String data, long sequence, long cursor) {
        return new EnvironmentTerminalInput(action, id, data, sequence, cursor, 101, 33);
    }

    static final class TerminalClock extends Clock {
        private final AtomicLong offset = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return System.currentTimeMillis() + offset.get();
        }
    }
}
