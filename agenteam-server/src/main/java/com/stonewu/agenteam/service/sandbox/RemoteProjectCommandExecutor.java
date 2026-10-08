package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.sandbox.request.ProjectExecutionRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectCommandExecutor;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用与独立节点共享项目存储，网络传输只包含命令和执行结果。
 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "remote")
public class RemoteProjectCommandExecutor implements ProjectCommandExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteProjectCommandExecutor.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String token;
    private final WorkspaceSettings settings;
    private final ObjectMapper json;

    public RemoteProjectCommandExecutor(WorkspaceSettings settings, ObjectMapper json,
                                        @Value("${execution.sandbox.remote-url:}") String url,
                                        @Value("${execution.sandbox.remote-token:}") String token) {
        endpoint = URI.create(url.endsWith("/") ? url : url + "/");
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]")
            .contains(endpoint.getHost() == null ? "" : endpoint.getHost());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !(endpoint.getScheme().equals("https") || endpoint.getScheme().equals("http") && local)
            || token == null || token.length() < 32 || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException(
                "独立沙盒需要有效的加密服务地址和至少三十二字符的访问凭据；本机地址可使用 HTTP");
        }
        this.token = token;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public SandboxExecutor.Result execute(ProjectLocation location, String callId, String command,
                                          String workingDirectory, Duration timeout, ToolCallControl control) {
        if (!settings.executeEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_DISABLED", "当前部署未启用沙盒执行。");
        }
        return send(new ProjectExecutionRequest(location, callId, command, workingDirectory,
                Math.min(timeout.toMillis(), settings.timeoutSeconds() * 1000L), settings.network(), false), timeout,
            control);
    }

    @Override
    public void recover(ProjectLocation location, Duration timeout, ToolCallControl control) {
        send(new ProjectExecutionRequest(location, null, null, null,
            Math.min(timeout.toMillis(), settings.timeoutSeconds() * 1000L),
            settings.network(), true), timeout, control);
    }

    private SandboxExecutor.Result send(ProjectExecutionRequest body, Duration timeout, ToolCallControl control) {
        String id = UUID.randomUUID().toString();
        URI address = endpoint.resolve("project-executions/" + id);
        var complete = new AtomicBoolean();
        try {
            control.requireActive();
            if (!body.recoverOnly()) {
                control.beforeSend();
            }
            var request = request(address).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            control.track(() -> {
                if (!complete.get()) {
                    future.cancel(true);
                    cancel(address, id);
                }
            });
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (var input = control.track(response.body())) {
                byte[] bytes = input.readNBytes(16385);
                if (bytes.length > 16384) {
                    throw new IllegalStateException("独立节点的执行结果超过允许范围");
                }
                if (response.statusCode() != 200) {
                    var error = json.readTree(bytes);
                    if (error.path("code").asText().equals("PROJECT_STORAGE_UNAVAILABLE")) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_STORAGE_UNAVAILABLE",
                            "执行节点无法读取项目目录，请检查两端是否使用同一份持久存储。");
                    }
                    throw new ApiException(
                        response.statusCode() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE,
                        "SANDBOX_REMOTE_UNAVAILABLE", "独立执行服务未能完成命令，请先检查项目中的已有结果再继续。");
                }
                var result = json.readValue(bytes, SandboxExecutor.Result.class);
                complete.set(true);
                return result;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            cancel(address, id);
            complete.set(true);
            control.requireActive();
            if (failure instanceof ApiException known) {
                throw known;
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_REMOTE_UNAVAILABLE",
                "命令执行结果尚无法确认，请先检查项目中的已有文件再继续。", failure);
        }
    }

    private HttpRequest.Builder request(URI address) {
        return HttpRequest.newBuilder(address).header("Authorization", "Bearer " + token);
    }

    private void cancel(URI address, String id) {
        try {
            client.sendAsync(request(address).timeout(Duration.ofSeconds(5)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding())
                .orTimeout(5, TimeUnit.SECONDS).join();
        } catch (RuntimeException failure) {
            LOG.warn("通知独立节点停止项目命令失败，执行编号 {}", id, failure);
        }
    }
}
