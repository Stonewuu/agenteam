package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;

import java.util.List;

/**
 * 引用位置采用固定字段或节点编号，不能从任意用户文字推测依赖。
 */
public final class DependencyFields {
    private DependencyFields() {
    }

    public static void array(List<DependencyBinding> target, JsonNode config, String field, String kind) {
        int ordinal = 0;
        for (JsonNode item : config.path(field)) {
            add(target, item, kind, field, ordinal++);
        }
    }

    public static void add(List<DependencyBinding> target, JsonNode value, String kind, String field, int ordinal) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.add(new DependencyBinding(value.asText(), kind, field, ordinal));
        }
    }
}
