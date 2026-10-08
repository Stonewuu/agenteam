package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import org.springframework.stereotype.Component;

/**
 * 集合版本固定其来源连接，改变主机或映射后不能悄悄读取另一个数据源。
 */
@Component
public class DataSourceHash {
    private final ObjectMapper json;
    private final ResourceJson resources;

    public DataSourceHash(ObjectMapper json, ResourceJson resources) {
        this.json = json;
        this.resources = resources;
    }

    public String calculate(JsonNode config) {
        var value = json.createObjectNode();
        value.set("sourceType", config.path("sourceType"));
        value.set("connection", config.path("connection"));
        value.set("credentialId", config.path("credentialId"));
        return resources.hash(value);
    }
}
