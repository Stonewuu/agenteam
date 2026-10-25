package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.plugin.WebPageContentMapper;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
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
        definition = new ToolDefinition("read_url", "读取指定网页的正文和链接，或纯文本、JSON 内容。动态网页可通过命令工具使用 agenteam-browser read 网址。", hash, input, output,
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
        Integer status = null;
        String trace = null;
        try (var scope = control.track(http.open(url, Map.of(), timeout));
             var exchange = scope.execute("GET", url, null, Map.of("Accept", "text/html, text/plain, application/json, application/xhtml+xml",
                 "User-Agent", "Mozilla/5.0 (compatible; AgenTeam/0.2; +https://github.com/Stonewuu/agenteam)"),
                 control::beforeSend)) {
            var response = exchange.response();
            status = response.code();
            String candidate = response.header("X-Request-Id", response.header("CF-Ray"));
            trace = candidate != null && candidate.matches("[A-Za-z0-9_-]{1,191}") ? candidate : null;
            if (List.of(408, 502, 503, 504).contains(response.code())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SERVER_UNAVAILABLE",
                    "目标网站暂时无法完成读取。");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_REQUEST_REJECTED",
                    "目标网站拒绝了本次读取。可尝试沙盒浏览器或其他公开来源；需要登录或验证时，请先确认已有访问权限。");
            }
            String mediaType = response.header("Content-Type", "").split(";", 2)[0].trim();
            if (!mediaType.startsWith("text/") && !mediaType.equals("application/json") && !mediaType.equals("application/xhtml+xml")) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID",
                    "该地址返回的不是可读取的文字内容。");
            }
            byte[] bytes = response.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_RESPONSE_TOO_LARGE",
                    "网页内容超过本次读取允许的大小。");
            }
            return json.tree(WebPageContentMapper.read(bytes, response.header("Content-Type"), response.request().url().toString()));
        } catch (IOException | RuntimeException failure) {
            var address = HttpUrl.parse(url);
            String code = failure instanceof ApiException known ? known.code() : "REMOTE_CONNECTION_FAILED";
            LOG.warn("网页读取失败，操作编号 {}，方法 GET，主机 {}，路径 {}，HTTP 状态 {}，错误码 {}，平台排障编号 {}",
                operationId, address == null ? "无效地址" : address.host(), address == null ? "—" : address.encodedPath(),
                status, code, trace, ToolFailureDetails.safe(failure, List.of(url)));
            if (failure instanceof ApiException known) {
                throw known;
            }
            if (failure instanceof CharacterCodingException || failure instanceof IllegalArgumentException) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", "网页的文字编码或内容类型无法解析。",
                    ToolFailureDetails.safe(failure, List.of(url)));
            }
            throw failed(ToolFailureDetails.safe(failure, List.of(url)));
        }
    }

    private static ApiException failed(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED", "无法读取该地址，请检查后重试。",
            cause);
    }
}
