package com.stonewu.agenteam.service.http;

import com.stonewu.agenteam.service.i18n.Messages;

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
                    throw ApiException.invalidField(entry.getKey(), Messages.text("error.http.patchFields.read.1"));
                }
                fields.add(entry.getKey());
            });
        }
        if (fields.isEmpty()) {
            throw ApiException.invalidField("body", Messages.text("error.http.patchFields.read.2"));
        }
        return Set.copyOf(fields);
    }
}
