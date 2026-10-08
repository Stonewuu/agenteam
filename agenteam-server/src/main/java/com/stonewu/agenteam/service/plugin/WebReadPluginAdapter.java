package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 读取指定地址的文本内容，不执行网页脚本，也不自动访问页面中的其他地址。
 */
@Component
public class WebReadPluginAdapter implements BuiltinPluginAdapter {
    private final RestrictedHttpClient http;
    private final ResourceJson json;
    private final ToolSchemaValidation schemas;
    private final ToolDefinition definition;

    public WebReadPluginAdapter(RestrictedHttpClient http, ResourceJson json, ToolSchemaValidation schemas) {
        this.http = http;
        this.json = json;
        this.schemas = schemas;
        JsonNode input = json.tree(Map.of("type", "object", "properties",
            Map.of("url", Map.of("type", "string", "minLength", 1, "maxLength", 2048)),
            "required", List.of("url"), "additionalProperties", false));
        JsonNode output = json.tree(Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string"),
                "content", Map.of("type", "string"), "mediaType", Map.of("type", "string")), "required",
            List.of("url", "content", "mediaType")));
        String hash = json.hash(json.tree(
            Map.of("name", "read_url", "inputSchema", input, "outputSchema", output, "operationClass", "read")));
        definition = new ToolDefinition("read_url", "读取指定网页、纯文本或 JSON 地址的内容", hash, input, output,
            json.tree(Map.of()),
            "read", false, false, false, List.of(), 30);
    }

    @Override
    public String code() {
        return "web_read";
    }

    @Override
    public String name() {
        return "网页读取";
    }

    @Override
    public String description() {
        return "读取指定地址的文字内容，用于任务中的资料参考。";
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(definition);
    }

    @Override
    public JsonNode call(String toolName, String operationId, JsonNode arguments, Duration timeout,
                         ToolCallControl control) {
        if (!toolName.equals(definition.name())) {
            throw ApiException.invalidField("toolName", "未提供此工具。");
        }
        schemas.arguments(definition.inputSchema(), arguments);
        String url = arguments.path("url").asText();
        try (var scope = control.track(http.open(url, Map.of(), timeout));
             var exchange = scope.execute("GET", url, null, Map.of("Accept", "text/plain, text/html, application/json"),
                 control::beforeSend)) {
            var response = exchange.response();
            if (List.of(408, 502, 503, 504).contains(response.code())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SERVER_UNAVAILABLE",
                    "目标网站暂时无法完成读取。");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_REQUEST_REJECTED",
                    "目标网站拒绝了本次读取，请检查地址和访问要求。");
            }
            String mediaType = response.header("Content-Type", "").split(";", 2)[0].trim();
            if (!mediaType.startsWith("text/") && !mediaType.equals("application/json")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID",
                    "该地址返回的不是可读取的文字内容。");
            }
            byte[] bytes = response.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_RESPONSE_TOO_LARGE",
                    "网页内容超过本次读取允许的大小。");
            }
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return json.tree(
                Map.of("url", response.request().url().toString(), "content", content, "mediaType", mediaType));
        } catch (CharacterCodingException invalid) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", "该地址返回的文字编码无法读取。",
                invalid);
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    private static ApiException failed() {
        return failed(null);
    }

    private static ApiException failed(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED", "无法读取该地址，请检查后重试。",
            cause);
    }
}
