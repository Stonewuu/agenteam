package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据 Java 请求对象的字段与参数约束校验输入，不加载文档或规则文件。
 */
public final class InputValidation {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private InputValidation() {
    }

    public static void request(HttpServletRequest request, Class<?> type) {
        if (!(request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body)) {
            throw ApiException.invalidField("body", "请提交完整的请求内容。");
        }
        read(body, type, "");
    }

    public static void validate(Object input) {
        validate(input, "");
    }

    public static void nonNullWhenPresent(HttpServletRequest request, String... fields) {
        if (!(request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body)) {
            throw ApiException.invalidField("body", "请提交完整的请求内容。");
        }
        for (String field : fields) {
            if (body.has(field) && body.get(field).isNull()) {
                throw ApiException.invalidField(field, "请提交此字段的内容，或省略此字段。");
            }
        }
    }

    public static void validate(Object input, String prefix) {
        if (input == null) {
            throw ApiException.invalidField(prefix.isEmpty() ? "body" : prefix, "请提交完整的内容。");
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (var violation : VALIDATOR.validate(input)) {
            String path = RequestValidationErrors.path(prefix, violation.getPropertyPath().toString());
            fields.putIfAbsent(path, List.of(RequestValidationErrors.constraint(violation)));
            if (fields.size() >= 20) {
                break;
            }
        }
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                RequestValidationErrors.summary(fields), Map.of(), fields);
        }
    }

    public static <T> T read(JsonNode input, Class<T> type, String field) {
        if (input == null || !input.isObject()) {
            throw ApiException.invalidField(field.isEmpty() ? "body" : field, "请提交完整的对象内容。");
        }
        T value;
        try {
            value = JSON.treeToValue(input, type);
        } catch (JsonMappingException invalid) {
            throw RequestValidationErrors.mapping(invalid, field);
        } catch (Exception invalid) {
            throw new IllegalStateException("请求对象无法读取", invalid);
        }
        validate(value, field);
        return value;
    }

    public static void fields(JsonNode value, String path, String... allowed) {
        if (value == null || !value.isObject()) {
            throw ApiException.invalidField(path, "请提交对象内容。");
        }
        var names = Set.of(allowed);
        value.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) {
                throw ApiException.invalidField(path + "." + name, "包含不支持的字段。");
            }
        });
    }

    public static void required(JsonNode value, String path, String... fields) {
        for (String field : fields) {
            if (!value.hasNonNull(field)) {
                throw ApiException.invalidField(path + "." + field, "请填写必填项。");
            }
        }
    }
}
