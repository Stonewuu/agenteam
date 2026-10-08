package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.sandbox.SandboxArchiveMapper;
import com.stonewu.agenteam.model.sandbox.request.SandboxExecutionRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxExecutor;
import com.stonewu.agenteam.service.workspace.WorkspaceFilesystem;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 独立执行节点通过受认证的文件传输获取资料，不依赖业务服务器的磁盘路径。
 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "remote")
public class RemoteSandboxExecutor implements SandboxExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteSandboxExecutor.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String token;
    private final WorkspaceSettings settings;
    private final SandboxArchiveMapper archives;

    public RemoteSandboxExecutor(WorkspaceSettings settings, ObjectMapper json,
                                 @Value("${execution.sandbox.remote-url:}") String url,
                                 @Value("${execution.sandbox.remote-token:}") String token) {
        endpoint = URI.create(url.endsWith("/") ? url : url + "/");
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]")
            .contains(endpoint.getHost() == null ? "" : endpoint.getHost());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !(endpoint.getScheme().equals("https") || endpoint.getScheme().equals("http") && loopback)
            || token == null || token.length() < 32 || token.contains("\n") || token.contains("\r")) {
            throw new IllegalArgumentException(
                "独立沙盒需要有效的加密服务地址和至少三十二字符的访问凭据；本机地址可使用 HTTP");
        }
        this.token = token;
        this.settings = settings;
        this.archives = new SandboxArchiveMapper(json, settings);
    }

    @Override
    public Result execute(Path source, Path destination, Path privateDirectory, String callId, String command,
                          String workingDirectory,
                          Duration timeout, ToolCallControl control) {
        if (!settings.executeEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SANDBOX_DISABLED", "当前部署未启用沙盒执行。");
        }
        String id = UUID.randomUUID().toString();
        URI address = endpoint.resolve("executions/" + id);
        Path transfer = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            WorkspacePaths.requireInside(privateDirectory, source);
            WorkspacePaths.requireInside(privateDirectory, destination);
            new WorkspaceFilesystem(source, settings, control::requireActive, false).validate();
            transfer = Files.createTempFile(privateDirectory, "sandbox-transfer-", ".zip");
            try (var output = Files.newOutputStream(transfer)) {
                archives.write(output, new SandboxExecutionRequest(callId, command, workingDirectory,
                        Math.min(timeout.toMillis(), settings.timeoutSeconds() * 1000L), settings.network()), source,
                    Set.of("inputs", "tool-results", "work", "outputs"), control);
            }
            var request = request(address).timeout(remaining(deadline)).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofFile(transfer)).build();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            control.track(() -> {
                future.cancel(true);
                cancel(address, id);
            });
            var response = future.get(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS);
            try (var body = control.track(response.body())) {
                if (response.statusCode() != 200) {
                    throw new ApiException(
                        response.statusCode() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE,
                        "SANDBOX_REMOTE_UNAVAILABLE",
                        response.statusCode() == 429 ? "执行环境正在处理其他任务，请稍后重试。" : "独立执行服务暂不可用，请稍后重试。");
                }
                var result = archives.read(body, destination, Set.of("work", "outputs"), Result.class, control);
                new WorkspaceFilesystem(destination, settings, control::requireActive, false).validate();
                return result;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            cancel(address, id);
            control.requireActive();
            if (failure instanceof ApiException known) {
                throw known;
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_REMOTE_UNAVAILABLE",
                "独立执行服务未能完成本次操作。", failure);
        } finally {
            if (transfer != null) {
                try {
                    Files.deleteIfExists(transfer);
                } catch (IOException failure) {
                    LOG.warn("独立执行的传输文件清理失败，调用编号 {}", callId, failure);
                }
            }
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
            LOG.warn("通知独立执行服务停止任务失败，执行编号 {}", id, failure);
        }
    }

    private Duration remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SANDBOX_TIMEOUT", "本次命令执行已经超时。");
        }
        return Duration.ofNanos(remaining);
    }
}
