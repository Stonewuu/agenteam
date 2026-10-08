package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.plugin.entity.PluginToolQueryRow;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.plugin.entity.PluginToolSource;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 工具结构跟随发布事务保存，调用时只读取对应企业和固定版本。
 */
@Repository
public class PluginToolMapper {
    private final PluginToolSqlMapper statements;
    private final ResourceJson json;
    private final ObjectMapper mapper;

    public PluginToolMapper(PluginToolSqlMapper statements, ResourceJson json, ObjectMapper mapper) {
        this.statements = statements;
        this.json = json;
        this.mapper = mapper;
    }

    public void publish(String enterprise, String version, List<ToolDefinition> selected, Instant now) {
        for (var tool : selected) {
            publish(enterprise, version, tool.name(), tool, null, now);
        }
    }

    public void publish(String enterprise, String version, String entryId, ToolDefinition tool, PluginToolSource source,
                        Instant now) {
        statements.publishPluginTool(UUID.randomUUID().toString(), enterprise, version, tool.name(), tool.description(),
            tool.schemaHash(),
            json.write(tool.inputSchema()),
            tool.outputSchema() == null || tool.outputSchema().isNull() ? null : json.write(tool.outputSchema()),
            json.write(tool.annotations()), tool.operationClass(), tool.supportsDeduplication(),
            tool.supportsResultQuery(), tool.supportsCancel(),
            json.write(json.tree(tool.redactPaths())), tool.timeoutSeconds(), Timestamp.from(now), entryId,
            source == null ? null : json.write(json.tree(source)));
    }

    public List<PluginToolRecord> list(String enterprise, String version) {
        return statements.listPluginTool(enterprise, version).stream().map(this::map).toList();
    }

    private PluginToolRecord map(PluginToolQueryRow rows) {
        var tool = new ToolDefinition(rows.getName(), rows.getDescription(), rows.getSchemaHash(),
            json.read(rows.getInputSchemaJson()), json.read(rows.getOutputSchemaJson()),
            json.read(rows.getAnnotationsJson()),
            rows.getOperationClass(), rows.getSupportsDeduplication(), rows.getSupportsResultQuery(),
            rows.getSupportsCancel(),
            mapper.convertValue(json.read(rows.getRedactPathsJson()), new TypeReference<>() {
            }), rows.getTimeoutSeconds());
        return new PluginToolRecord(rows.getId(), rows.getEnterpriseId(), rows.getPluginVersionId(), rows.getEnabled(),
            tool, rows.getEntryId(),
            rows.getSourceJson() == null ? null : mapper.convertValue(json.read(rows.getSourceJson()),
                PluginToolSource.class));
    }
}
