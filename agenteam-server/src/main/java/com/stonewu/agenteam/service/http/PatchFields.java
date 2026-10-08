package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashSet;
import java.util.Set;

/**
 * 区分没有提交字段与明确提交空值，局部修改不能覆盖未编辑内容。
 */
public final class PatchFields {
    private PatchFields() {
    }

    public static Set<String> read(HttpServletRequest request, Set<String> nullable) {
        Set<String> fields = new HashSet<>();
        if (request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode body) {
            body.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull() && !nullable.contains(entry.getKey())) {
                    throw ApiException.invalidField(entry.getKey(), "此项不能设置为空值。");
                }
                fields.add(entry.getKey());
            });
        }
        if (fields.isEmpty()) {
            throw ApiException.invalidField("body", "请至少修改一项内容。");
        }
        return Set.copyOf(fields);
    }
}
