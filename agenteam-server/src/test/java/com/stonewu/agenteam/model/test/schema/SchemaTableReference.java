package com.stonewu.agenteam.model.test.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * 结构检查只能访问原始数据库快照中登记的业务表。
 */
public record SchemaTableReference(String name) {
    private static final JsonNode ALLOWED_TABLES = loadTables();

    public SchemaTableReference {
        if (name == null || !ALLOWED_TABLES.has(name)) {
            throw new IllegalArgumentException("检查目标不属于已登记的业务表");
        }
    }

    private static JsonNode loadTables() {
        try (var stream = new ClassPathResource("schema/initialization-contract.json").getInputStream()) {
            return new ObjectMapper().readTree(stream).get("definitionHashes");
        } catch (IOException failure) {
            throw new IllegalStateException("原始数据库结构快照无法读取", failure);
        }
    }
}
