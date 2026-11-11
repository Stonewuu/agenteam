package com.stonewu.agenteam.service.http;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.i18n.ApplicationMessageInterpolator;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider().configure()
        .messageInterpolator(new ApplicationMessageInterpolator()).buildValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final ObjectMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private InputValidation() {
    }

    public static void request(HttpServletRequest request, Class<?> type) {
        if (!(request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body)) {
            throw ApiException.invalidField("body", Messages.text("error.http.inputValidation.request.1"));
        }
        read(body, type, "");
    }

    public static void validate(Object input) {
        validate(input, "");
    }

    public static void nonNullWhenPresent(HttpServletRequest request, String... fields) {
        if (!(request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body)) {
            throw ApiException.invalidField("body", Messages.text("error.http.inputValidation.request.1"));
        }
        for (String field : fields) {
            if (body.has(field) && body.get(field).isNull()) {
                throw ApiException.invalidField(field, Messages.text("error.http.inputValidation.nonNullWhenPresent.1"));
            }
        }
    }

    public static void validate(Object input, String prefix) {
        if (input == null) {
            throw ApiException.invalidField(prefix.isEmpty() ? "body" : prefix, Messages.text("error.http.inputValidation.validate.1"));
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
            throw ApiException.invalidField(field.isEmpty() ? "body" : field, Messages.text("error.http.inputValidation.read.1"));
        }
        T value;
        try {
            value = JSON.treeToValue(input, type);
        } catch (JsonMappingException invalid) {
            throw RequestValidationErrors.mapping(invalid, field);
        } catch (Exception invalid) {
            throw new IllegalStateException(Messages.text("error.http.inputValidation.read.2"), invalid);
        }
        validate(value, field);
        return value;
    }

    public static void fields(JsonNode value, String path, String... allowed) {
        if (value == null || !value.isObject()) {
            throw ApiException.invalidField(path, Messages.text("error.http.inputValidation.fields.1"));
        }
        var names = Set.of(allowed);
        value.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) {
                throw ApiException.invalidField(path + "." + name, Messages.text("error.http.inputValidation.fields.2"));
            }
        });
    }

    public static void required(JsonNode value, String path, String... fields) {
        for (String field : fields) {
            if (!value.hasNonNull(field)) {
                throw ApiException.invalidField(path + "." + field, Messages.text("validation.required"));
            }
        }
    }
}
