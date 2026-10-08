package com.stonewu.agenteam.mapper.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.plugin.entity.PluginToolQueryRow;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * PluginToolMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface PluginToolSqlMapper extends MPJBaseMapper<PluginToolRow> {
    default int publishPluginTool(String id, String enterprise, String version, String name, String description,
                                  String schemaHash, String inputSchemaJson, String outputSchemaJson,
                                  String annotationsJson, String operationClass, boolean supportsDeduplication,
                                  boolean supportsResultQuery, boolean supportsCancel, String redactPathsJson,
                                  int timeoutSeconds, Timestamp now, String entryId, String sourceJson) {
        var databaseRow = new PluginToolRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setPluginVersionId(version);
        databaseRow.setEntryId(entryId);
        databaseRow.setSourceJson(sourceJson);
        databaseRow.setName(name);
        databaseRow.setDescription(description);
        databaseRow.setSchemaHash(schemaHash);
        databaseRow.setInputSchemaJson(inputSchemaJson);
        databaseRow.setOutputSchemaJson(outputSchemaJson);
        databaseRow.setAnnotationsJson(annotationsJson);
        databaseRow.setOperationClass(operationClass);
        databaseRow.setEnabled(1);
        databaseRow.setSupportsDeduplication((supportsDeduplication ? 1 : 0));
        databaseRow.setSupportsResultQuery((supportsResultQuery ? 1 : 0));
        databaseRow.setSupportsCancel((supportsCancel ? 1 : 0));
        databaseRow.setRedactPathsJson(redactPathsJson);
        databaseRow.setTimeoutSeconds(timeoutSeconds);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default List<PluginToolQueryRow> listPluginTool(String enterprise, String version) {
        var criteria = new LambdaQueryWrapper<PluginToolRow>().orderByAsc(PluginToolRow::getName)
            .eq(PluginToolRow::getEnterpriseId, enterprise).eq(PluginToolRow::getPluginVersionId, version);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new PluginToolQueryRow();
            mappedRow.setEntryId(storedRow.getEntryId());
            mappedRow.setSourceJson(storedRow.getSourceJson());
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getPluginVersionId() != null) {
                mappedRow.setPluginVersionId(storedRow.getPluginVersionId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getDescription() != null) {
                mappedRow.setDescription(storedRow.getDescription());
            }
            if (storedRow.getSchemaHash() != null) {
                mappedRow.setSchemaHash(storedRow.getSchemaHash());
            }
            if (storedRow.getInputSchemaJson() != null) {
                mappedRow.setInputSchemaJson(storedRow.getInputSchemaJson());
            }
            if (storedRow.getOutputSchemaJson() != null) {
                mappedRow.setOutputSchemaJson(storedRow.getOutputSchemaJson());
            }
            if (storedRow.getAnnotationsJson() != null) {
                mappedRow.setAnnotationsJson(storedRow.getAnnotationsJson());
            }
            if (storedRow.getOperationClass() != null) {
                mappedRow.setOperationClass(storedRow.getOperationClass());
            }
            if (storedRow.getEnabled() != null) {
                mappedRow.setEnabled((storedRow.getEnabled() != null && storedRow.getEnabled() != 0));
            }
            if (storedRow.getSupportsDeduplication() != null) {
                mappedRow.setSupportsDeduplication(
                    (storedRow.getSupportsDeduplication() != null && storedRow.getSupportsDeduplication() != 0));
            }
            if (storedRow.getSupportsResultQuery() != null) {
                mappedRow.setSupportsResultQuery(
                    (storedRow.getSupportsResultQuery() != null && storedRow.getSupportsResultQuery() != 0));
            }
            if (storedRow.getSupportsCancel() != null) {
                mappedRow.setSupportsCancel(
                    (storedRow.getSupportsCancel() != null && storedRow.getSupportsCancel() != 0));
            }
            if (storedRow.getRedactPathsJson() != null) {
                mappedRow.setRedactPathsJson(storedRow.getRedactPathsJson());
            }
            if (storedRow.getTimeoutSeconds() != null) {
                mappedRow.setTimeoutSeconds(storedRow.getTimeoutSeconds());
            }
            return mappedRow;
        }).toList();
    }
}
