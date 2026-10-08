package com.stonewu.agenteam.service.sandbox;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 只传递固定类型及工作空间摘要，不允许调用方指定任意容器名称或主机路径。
 */
@Component
@Primary
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "remote")
public class RemoteSandboxContainerCleanup implements SandboxContainerCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteSandboxContainerCleanup.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String token;

    public RemoteSandboxContainerCleanup(@Value("${execution.sandbox.remote-url:}") String url,
                                         @Value("${execution.sandbox.remote-token:}") String token) {
        endpoint = URI.create(url.endsWith("/") ? url : url + "/");
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]")
            .contains(endpoint.getHost() == null ? "" : endpoint.getHost());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !(endpoint.getScheme().equals("https") || endpoint.getScheme().equals("http") && local)
            || token == null || token.length() < 32 || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException(
                "独立沙箱清理需要加密服务地址和至少三十二字符的访问凭据；本机地址可使用 HTTP");
        }
        this.token = token;
    }

    @Override
    public void removeUserContainer(String workspaceId, Duration timeout) {
        remove("user", workspaceId, timeout, null);
    }

    @Override
    public void removeForWorkspace(String scope, Duration timeout, ToolCallControl control) {
        remove("workspace", scope, timeout, control);
    }

    private void remove(String type, String scope, Duration timeout, ToolCallControl control) {
        if (scope == null || !scope.matches("[0-9a-f]{64}")) {
            throw WorkspacePaths.invalid();
        }
        if (control != null) {
            control.requireActive();
        }
        CompletableFuture<HttpResponse<Void>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve("container-cleanup/" + type + "/" + scope))
                .header("Authorization", "Bearer " + token).timeout(timeout).DELETE().build();
            pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            if (control != null) {
                var tracked = pending;
                control.track(() -> tracked.cancel(true));
            }
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 204) {
                throw new IOException("独立执行节点未确认容器清理完成，响应状态 " + response.statusCode());
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("独立节点清理容器失败，清理类型 {}，工作空间编号 {}", type, scope, failure);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SANDBOX_CLEANUP_UNAVAILABLE",
                "命令运行环境尚未清理完成，请检查独立执行服务后重试。", failure);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }
}
