package com.stonewu.agenteam.mapper.modelprofile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 读取兼容模型列表及提供方明确返回的长度、输入类型和参数能力。
 */
@Component
public class RemoteModelMapper {
    private final ObjectMapper json;

    public RemoteModelMapper(ObjectMapper json) {
        this.json = json;
    }

    public List<RemoteModelView> read(byte[] body) {
        return readPage(parse(body), "openai").items();
    }

    public JsonNode parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException invalid) {
            throw invalidResponse(invalid);
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse(null);
        }
        return root;
    }

    public ModelPage readPage(JsonNode root, String protocol) {
        boolean gemini = protocol.equals("gemini");
        boolean anthropic = protocol.equals("anthropic");
        JsonNode data = root.get(gemini ? "models" : "data");
        if (data == null || !data.isArray()) {
            throw invalidResponse(null);
        }
        var models = new LinkedHashMap<String, RemoteModelView>();
        for (JsonNode item : data) {
            String id = text(item.path(gemini ? "name" : "id"), 128);
            if (id == null) {
                continue;
            }
            String name = text(item.path(gemini ? "displayName" : anthropic ? "display_name" : "name"), 80);
            JsonNode parameters = item.path("supported_parameters");
            Integer context = length(10_000_000, item.path("inputTokenLimit"), item.path("max_input_tokens"), item.path("context_length"), item.path("max_context_length"),
                item.path("top_provider").path("context_length"));
            Integer output = length(1_000_000, item.path("outputTokenLimit"), item.path("max_output_tokens"),
                item.path("top_provider").path("max_completion_tokens"));
            // 相互矛盾的限制不能作为可直接保存的配置。
            if (context != null && output != null && output > context) {
                output = null;
            }
            models.putIfAbsent(id, new RemoteModelView(id, name == null ? id : name, context, output,
                inputTypes(item.path("architecture").path("input_modalities")),
                capability(item.path("supports_tools"), parameters, "tools"),
                capability(item.path("supports_temperature"), parameters, "temperature")));
        }
        if (!data.isEmpty() && models.isEmpty()) {
            throw invalidResponse(null);
        }
        String next = gemini ? text(root.path("nextPageToken"), 2048) : text(root.path("last_id"), 2048);
        boolean more = gemini ? root.hasNonNull("nextPageToken") && !root.path("nextPageToken").asText().isBlank()
            : root.path("has_more").asBoolean(false);
        if (more && (next == null || (!gemini && !anthropic))) {
            throw invalidResponse(null);
        }
        return new ModelPage(models.values().stream().sorted(Comparator.comparing(RemoteModelView::id)).toList(), next, more);
    }

    public record ModelPage(List<RemoteModelView> items, String next, boolean hasMore) {
    }

    private String text(JsonNode value, int max) {
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue().trim();
        return text.isEmpty() || text.codePointCount(0, text.length()) > max
            || text.codePoints().anyMatch(Character::isISOControl) ? null : text;
    }

    private Integer length(int max, JsonNode... values) {
        for (JsonNode value : values) {
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 128 && value.intValue() <= max) {
                return value.intValue();
            }
        }
        return null;
    }

    private Boolean capability(JsonNode explicit, JsonNode parameters, String name) {
        if (explicit.isBoolean()) {
            return explicit.booleanValue();
        }
        if (!parameters.isArray()) {
            return null;
        }
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.asText())) {
                return true;
            }
        }
        return false;
    }

    private List<String> inputTypes(JsonNode values) {
        if (!values.isArray()) {
            return null;
        }
        var result = new ArrayList<String>();
        for (JsonNode value : values) {
            String type = value.asText();
            if ((type.equals("text") || type.equals("image")) && !result.contains(type)) {
                result.add(type);
            }
        }
        return List.copyOf(result);
    }

    private ApiException invalidResponse(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_INVALID",
            "提供方未返回有效的模型列表，可重试或手动填写模型标识。", cause);
    }
}
