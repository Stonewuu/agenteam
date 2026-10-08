package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;

import java.util.List;

/**
 * 各资源类型负责自身字段和业务规则；发布检查不发起外部调用。
 */
public interface ResourceConfigValidator {
    ResourceKind kind();

    default void draft(JsonNode config) {
    }

    default void validatePublished(JsonNode config) {
        draft(config);
    }

    default void publish(AuthContext actor, ResourceRecord resource) {
        use(actor, resource.config());
    }

    default void use(AuthContext actor, JsonNode config) {
    }

    default List<DependencyBinding> dependencies(JsonNode config) {
        return List.of();
    }
}
