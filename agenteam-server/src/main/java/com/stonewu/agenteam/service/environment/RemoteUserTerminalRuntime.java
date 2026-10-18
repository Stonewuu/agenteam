package com.stonewu.agenteam.service.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.sandbox.request.EnvironmentTerminalRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ExceptionDiagnostics;
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
import java.time.Duration;
import java.util.Set;

/** 不在应用进程访问 Docker，所有容器管理操作都交给所属执行节点。 */
@Component
@ConditionalOnProperty(name = "execution.sandbox.backend", havingValue = "remote")
public class RemoteUserTerminalRuntime implements UserTerminalRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteUserTerminalRuntime.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String token;
    private final ObjectMapper json;

    public RemoteUserTerminalRuntime(ObjectMapper json,
            @Value("${execution.sandbox.remote-url:}") String url,
            @Value("${execution.sandbox.remote-token:}") String token) {
        URI base = URI.create(url.endsWith("/") ? url : url + "/");
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
            || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && local)
            || token == null || token.length() < 32 || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("独立终端需要有效的服务地址和访问凭据。");
        }
        this.endpoint = base.resolve("terminals");
        this.token = token;
        this.json = json;
    }

    @Override
    public EnvironmentTerminalView exchange(ProjectLocation project, EnvironmentTerminalInput input) {
        Integer httpStatus = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(new EnvironmentTerminalRequest(project, input)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            httpStatus = response.statusCode();
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(262145);
                if (bytes.length > 262144) {
                    throw new IOException("独立终端响应超过允许范围");
                }
                if (response.statusCode() != 200) {
                    String code = json.readTree(bytes).path("code").asText();
                    String safeCode = code.matches("[A-Z_]{1,80}") ? code : "UNKNOWN";
                    LOG.warn("独立终端失败，工作空间编号 {}，方法 POST，路径 {}，HTTP 状态 {}，错误码 {}",
                        project.workspaceId(), endpoint.getPath(), response.statusCode(), safeCode);
                    if ("ENVIRONMENT_BUSY".equals(code)) {
                        throw new ApiException(HttpStatus.CONFLICT, code, "环境中仍有任务或命令正在运行，请结束后再停止。");
                    }
                    if ("SANDBOX_CAPACITY".equals(code)) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, "暂无可用执行环境，请稍后连接。");
                    }
                    throw new ApiException(response.statusCode() == 409 ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE,
                        safeCode, "终端连接暂时不可用，请重新连接。");
                }
                var view = json.readValue(bytes, EnvironmentTerminalView.class);
                if (view == null || !Set.of("starting", "running", "closing", "closed", "error").contains(view.status() == null ? "" : view.status())
                    || view.id() == null || view.output() == null) {
                    throw new IOException("独立终端响应缺少有效状态");
                }
                return view;
            }
        } catch (ApiException failure) {
            throw failure;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = failure instanceof JsonProcessingException ? ExceptionDiagnostics.withoutMessages(failure) : failure;
            LOG.warn("独立终端请求失败，工作空间编号 {}，方法 POST，路径 {}，HTTP 状态 {}", project.workspaceId(), endpoint.getPath(), httpStatus, cause);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENVIRONMENT_UNAVAILABLE", "终端暂时无法连接，请稍后重试。", cause);
        }
    }
}
