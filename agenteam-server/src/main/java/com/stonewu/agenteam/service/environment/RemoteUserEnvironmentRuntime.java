package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stonewu.agenteam.model.environment.response.EnvironmentView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.sandbox.request.EnvironmentRequest;
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
public class RemoteUserEnvironmentRuntime implements UserEnvironmentRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteUserEnvironmentRuntime.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String token;
    private final ObjectMapper json;

    public RemoteUserEnvironmentRuntime(ObjectMapper json,
            @Value("${execution.sandbox.remote-url:}") String url,
            @Value("${execution.sandbox.remote-token:}") String token) {
        URI base = URI.create(url.endsWith("/") ? url : url + "/");
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
            || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && local)
            || token == null || token.length() < 32 || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException(Messages.text("error.environment.remoteUserEnvironmentRuntime.init.1"));
        }
        this.endpoint = base.resolve("environments");
        this.token = token;
        this.json = json;
    }

    @Override
    public EnvironmentView manage(String action, ProjectLocation project) {
        Integer httpStatus = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(new EnvironmentRequest(action, project)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            httpStatus = response.statusCode();
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(16385);
                if (bytes.length > 16384) {
                    throw new IOException(Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.1"));
                }
                if (response.statusCode() != 200) {
                    String code = json.readTree(bytes).path("code").asText();
                    String safeCode = code.matches("[A-Z_]{1,80}") ? code : "UNKNOWN";
                    LOG.warn(Messages.log("log.environment.remoteUserEnvironmentRuntime.manage.1"),
                        project.workspaceId(), endpoint.getPath(), response.statusCode(), safeCode);
                    if ("ENVIRONMENT_BUSY".equals(code)) {
                        throw new ApiException(HttpStatus.CONFLICT, code, Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.2"));
                    }
                    if ("SANDBOX_CAPACITY".equals(code)) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.3"));
                    }
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENVIRONMENT_UNAVAILABLE", Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.4"));
                }
                var view = json.readValue(bytes, EnvironmentView.class);
                if (view == null || !Set.of("running", "stopped", "stopping").contains(view.status() == null ? "" : view.status())
                    || view.idleSeconds() < 1) {
                    throw new IOException(Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.5"));
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
            LOG.warn(Messages.log("log.environment.remoteUserEnvironmentRuntime.manage.2"), project.workspaceId(), endpoint.getPath(), httpStatus, cause);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENVIRONMENT_UNAVAILABLE", Messages.text("error.environment.remoteUserEnvironmentRuntime.manage.4"), cause);
        }
    }
}
