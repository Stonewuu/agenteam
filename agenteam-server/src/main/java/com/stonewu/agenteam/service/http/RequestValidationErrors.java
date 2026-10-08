package com.stonewu.agenteam.service.http;

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
            reason = "不支持此字段，请刷新页面后重新提交。";
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
            return "不能小于 " + min.value() + "。";
        }
        if (annotation instanceof Max max) {
            return "不能大于 " + max.value() + "。";
        }
        if (annotation instanceof DecimalMin min) {
            return "必须" + (min.inclusive() ? "大于或等于 " : "大于 ") + min.value() + "。";
        }
        if (annotation instanceof DecimalMax max) {
            return "必须" + (max.inclusive() ? "小于或等于 " : "小于 ") + max.value() + "。";
        }
        if (annotation instanceof Size size) {
            String subject = violation.getInvalidValue() instanceof CharSequence ? "字符数" : "条目数";
            if (size.min() == size.max()) {
                return subject + "必须为 " + size.min() + "。";
            }
            if (size.min() == 0) {
                return subject + "不能超过 " + size.max() + "。";
            }
            if (size.max() == Integer.MAX_VALUE) {
                return subject + "不能少于 " + size.min() + "。";
            }
            return subject + "需要在 " + size.min() + "～" + size.max() + " 之间。";
        }
        return message;
    }

    static String summary(Map<String, List<String>> fields) {
        if (fields.isEmpty()) {
            return "请求内容不符合要求，请检查填写内容。";
        }
        if (fields.size() > 1) {
            return "有 " + fields.size() + " 个字段需要修改，请查看具体提示。";
        }
        var field = fields.entrySet().iterator().next();
        return "字段「" + field.getKey() + "」：" + field.getValue().getFirst();
    }

    private static String expectedType(Class<?> type) {
        if (type == null) {
            return "类型不正确，请检查此字段。";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "需要填写布尔值（true 或 false）。";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class || type == Short.class
            || type == short.class || type == Byte.class || type == byte.class || type == BigInteger.class) {
            return "需要填写整数，不能使用文字或小数。";
        }
        if (Number.class.isAssignableFrom(type) || type == double.class || type == float.class) {
            return "需要填写数字，不能使用文字。";
        }
        if (CharSequence.class.isAssignableFrom(type) || type == char.class || type == Character.class) {
            return "需要填写文字。";
        }
        if (type.isEnum()) {
            return "请选择有效的选项。";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "需要提交列表。";
        }
        return "需要提交包含各字段的完整对象。";
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
