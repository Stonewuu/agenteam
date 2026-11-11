package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.plugin.WebPageContentMapper;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApplicationInfoService;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.agenteam.service.tool.ToolFailureDetails;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 读取指定地址的文本内容，不执行网页脚本，也不自动访问页面中的其他地址。
 */
@Component
public class WebReadPluginAdapter implements BuiltinPluginAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(WebReadPluginAdapter.class);
    private final RestrictedHttpClient http;
    private final ResourceJson json;
    private final ToolSchemaValidation schemas;
    private final ToolDefinition definition;
    private final ApplicationInfoService applicationInfo;

    public WebReadPluginAdapter(RestrictedHttpClient http, ResourceJson json, ToolSchemaValidation schemas,
                               ApplicationInfoService applicationInfo) {
        this.http = http;
        this.json = json;
        this.schemas = schemas;
        this.applicationInfo = applicationInfo;
        JsonNode input = json.tree(Map.of("type", "object", "properties",
            Map.of("url", Map.of("type", "string", "minLength", 1, "maxLength", 2048)),
            "required", List.of("url"), "additionalProperties", false));
        JsonNode output = json.tree(Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string"),
                "content", Map.of("type", "string"), "mediaType", Map.of("type", "string")), "required",
            List.of("url", "content", "mediaType")));
        String hash = json.hash(json.tree(
            Map.of("name", "read_url", "inputSchema", input, "outputSchema", output, "operationClass", "read")));
        definition = new ToolDefinition("read_url", Messages.source("text.plugin.webReadPluginAdapter.init.1"), hash, input, output,
            json.tree(Map.of()),
            "read", false, false, false, List.of(), 30);
    }

    @Override
    public String code() {
        return "web_read";
    }

    @Override
    public String name() {
        return BuiltinPluginPresentation.name(code());
    }

    @Override
    public String description() {
        return BuiltinPluginPresentation.description(code());
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(definition);
    }

    @Override
    public JsonNode call(String toolName, String operationId, JsonNode arguments, Duration timeout,
                         ToolCallControl control) {
        if (!toolName.equals(definition.name())) {
            throw ApiException.invalidField("toolName", Messages.text("error.plugin.webReadPluginAdapter.call.1"));
        }
        schemas.arguments(definition.inputSchema(), arguments);
        String url = arguments.path("url").asText();
        Integer status = null;
        String trace = null;
        try (var scope = control.track(http.open(url, Map.of(), timeout));
             var exchange = scope.execute("GET", url, null, Map.of("Accept", "text/html, text/plain, application/json, application/xhtml+xml",
                 "User-Agent", applicationInfo.webReaderUserAgent()),
                 control::beforeSend)) {
            var response = exchange.response();
            status = response.code();
            String candidate = response.header("X-Request-Id", response.header("CF-Ray"));
            trace = candidate != null && candidate.matches("[A-Za-z0-9_-]{1,191}") ? candidate : null;
            if (List.of(408, 502, 503, 504).contains(response.code())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SERVER_UNAVAILABLE",
                    Messages.text("error.plugin.webReadPluginAdapter.call.2"));
            }
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_REQUEST_REJECTED",
                    Messages.text("error.plugin.webReadPluginAdapter.call.3"));
            }
            String mediaType = response.header("Content-Type", "").split(";", 2)[0].trim();
            if (!mediaType.startsWith("text/") && !mediaType.equals("application/json") && !mediaType.equals("application/xhtml+xml")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID",
                    Messages.text("error.plugin.webReadPluginAdapter.call.4"));
            }
            byte[] bytes = response.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_RESPONSE_TOO_LARGE",
                    Messages.text("error.plugin.webReadPluginAdapter.call.5"));
            }
            return json.tree(WebPageContentMapper.read(bytes, response.header("Content-Type"), response.request().url().toString()));
        } catch (IOException | RuntimeException failure) {
            var address = HttpUrl.parse(url);
            String code = failure instanceof ApiException known ? known.code() : "REMOTE_CONNECTION_FAILED";
            LOG.warn(Messages.log("log.plugin.webReadPluginAdapter.call.1"),
                operationId, address == null ? Messages.log("log.plugin.webReadPluginAdapter.call.2") : address.host(), address == null ? "—" : address.encodedPath(),
                status, code, trace, ToolFailureDetails.safe(failure, List.of(url)));
            if (failure instanceof ApiException known) {
                throw known;
            }
            if (failure instanceof CharacterCodingException || failure instanceof IllegalArgumentException) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", Messages.text("error.plugin.webReadPluginAdapter.call.6"),
                    ToolFailureDetails.safe(failure, List.of(url)));
            }
            throw failed(ToolFailureDetails.safe(failure, List.of(url)));
        }
    }

    private static ApiException failed(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED", Messages.text("error.plugin.webReadPluginAdapter.failed.1"),
            cause);
    }
}
