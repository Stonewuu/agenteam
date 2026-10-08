package com.stonewu.agenteam.mapper.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.response.ConnectionCheckView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件与数据源共用最后一次实际连接检查，配置修改后不展示旧配置的结论。
 */
@Component
public class ResourceConnectionCheckMapper {
    private final ObjectMapper json;

    public ResourceConnectionCheckMapper(ObjectMapper json) {
        this.json = json;
    }

    public ConnectionCheckView lastResult(ResourceRecord resource) {
        if (resource.kind() != ResourceKind.PLUGIN && resource.kind() != ResourceKind.DATA) {
            return null;
        }
        if (resource.validation() == null || !resource.configHash()
            .equals(resource.validation().path("configHash").asText())) {
            return null;
        }
        var connection = resource.validation().path("connection");
        if (!connection.hasNonNull("checkedAt")) {
            return null;
        }
        return new ConnectionCheckView(connection.path("checkedAt").asText(),
            connection.path("status").asText().equals("succeeded"),
            connection.path("summary").asText(), connection.path("durationMs").asLong(),
            connection.path("toolChanges").isArray() ? json.convertValue(connection.path("toolChanges"),
                new TypeReference<>() {
                }) : List.of());
    }
}
