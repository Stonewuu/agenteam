package com.stonewu.agenteam.service.http;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.model.i18n.entity.UiLanguage;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 只公开字段和填写要求，原始异常及被拒绝的值不进入页面提示。
 */
final class RequestValidationErrors {
    private RequestValidationErrors() {
    }

    static ApiException mapping(JsonMappingException failure, String prefix) {
        var references = failure.getPath().stream()
            .filter(reference -> reference.getFieldName() != null || reference.getIndex() >= 0)
            .map(reference -> reference.getFieldName() != null ? reference.getFieldName() : Integer.toString(
                reference.getIndex())).toList();
        return mapping(failure, prefix, references,
            failure instanceof UnrecognizedPropertyException unknown ? unknown.getPropertyName() : null,
            failure instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null);
    }

    static ApiException mapping(Throwable failure, String prefix, List<String> references, String unknownProperty,
                                Class<?> expectedType) {
        String path = prefix;
        for (String reference : references) {
            path = append(path, reference);
        }
        String reason;
        if (unknownProperty != null) {
            String name = safe(unknownProperty);
            if (!path.equals(name) && !path.endsWith("." + name)) {
                path = append(path, name);
            }
            reason = Messages.text("error.http.requestValidationErrors.mapping.1");
        } else {
            reason = expectedType(expectedType);
        }
        if (path.isEmpty()) {
            path = "body";
        }
        var fields = Map.of(path, List.of(reason));
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", summary(fields), Map.of(), fields,
            failure);
    }

    static String path(String prefix, String path) {
        return append(prefix, path.replaceAll("\\[(\\d+)]", ".$1").replaceAll("\\.<[^>]+>", ""));
    }

    static String constraint(ConstraintViolation<?> violation) {
        var annotation = violation.getConstraintDescriptor().getAnnotation();
        String message = violation.getMessage();
        if (annotation instanceof Min min) {
            return Messages.text("error.http.requestValidationErrors.constraint.1", min.value());
        }
        if (annotation instanceof Max max) {
            return Messages.text("error.http.requestValidationErrors.constraint.2", max.value());
        }
        if (annotation instanceof DecimalMin min) {
            return Messages.text(min.inclusive() ? "validation.decimal.minInclusive" : "validation.decimal.minExclusive", min.value());
        }
        if (annotation instanceof DecimalMax max) {
            return Messages.text(max.inclusive() ? "validation.decimal.maxInclusive" : "validation.decimal.maxExclusive", max.value());
        }
        if (annotation instanceof Size size) {
            String subject = violation.getInvalidValue() instanceof CharSequence ? Messages.text("error.http.requestValidationErrors.constraint.8") : Messages.text("error.http.requestValidationErrors.constraint.9");
            if (size.min() == size.max()) {
                return Messages.text("error.http.requestValidationErrors.constraint.10", subject, size.min());
            }
            if (size.min() == 0) {
                return Messages.text("error.http.requestValidationErrors.constraint.11", subject, size.max());
            }
            if (size.max() == Integer.MAX_VALUE) {
                return Messages.text("error.http.requestValidationErrors.constraint.12", subject, size.min());
            }
            return Messages.text("error.http.requestValidationErrors.constraint.13", subject, size.min(), size.max());
        }
        return message;
    }

    static String summary(Map<String, List<String>> fields) {
        if (fields.isEmpty()) {
            return Messages.text("error.http.requestValidationErrors.summary.1");
        }
        if (fields.size() > 1) {
            return Messages.text("error.http.requestValidationErrors.summary.2", fields.size());
        }
        var field = fields.entrySet().iterator().next();
        return Messages.text("error.http.requestValidationErrors.summary.3", field.getKey(), field.getValue().getFirst());
    }

    static String localizedSummary(String message, Map<String, List<String>> original,
                                    Map<String, List<String>> translated) {
        if (original.size() == 1) {
            var field = original.entrySet().iterator().next();
            for (var language : UiLanguage.values()) {
                String candidate = Messages.in(language, "error.http.requestValidationErrors.summary.3",
                    field.getKey(), field.getValue().getFirst());
                if (candidate.equals(message)) {
                    return summary(translated);
                }
            }
        }
        return message;
    }

    private static String expectedType(Class<?> type) {
        if (type == null) {
            return Messages.text("error.http.requestValidationErrors.expectedType.1");
        }
        if (type == Boolean.class || type == boolean.class) {
            return Messages.text("error.http.requestValidationErrors.expectedType.2");
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class || type == Short.class
            || type == short.class || type == Byte.class || type == byte.class || type == BigInteger.class) {
            return Messages.text("error.http.requestValidationErrors.expectedType.3");
        }
        if (Number.class.isAssignableFrom(type) || type == double.class || type == float.class) {
            return Messages.text("error.http.requestValidationErrors.expectedType.4");
        }
        if (CharSequence.class.isAssignableFrom(type) || type == char.class || type == Character.class) {
            return Messages.text("error.http.requestValidationErrors.expectedType.5");
        }
        if (type.isEnum()) {
            return Messages.text("error.http.requestValidationErrors.expectedType.6");
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return Messages.text("error.http.requestValidationErrors.expectedType.7");
        }
        return Messages.text("error.http.requestValidationErrors.expectedType.8");
    }

    private static String append(String prefix, String field) {
        if (field.isEmpty()) {
            return prefix.isEmpty() ? "body" : prefix;
        }
        return prefix.isEmpty() ? safe(field) : prefix + "." + safe(field);
    }

    private static String safe(String value) {
        String result = value.replaceAll("\\p{Cntrl}", "");
        return result.length() > 160 ? result.substring(0, 160) : result;
    }
}
