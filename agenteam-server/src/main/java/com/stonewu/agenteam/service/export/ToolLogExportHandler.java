package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.export.CsvExportMapper;
import com.stonewu.agenteam.mapper.export.ExportDefinitionMapper;
import com.stonewu.agenteam.mapper.tool.ToolLogMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.QueryTimeRangeService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.tool.ToolLogService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 工具调用导出同时满足查看和导出范围，继续留在公共版。 */
@Component
public class ToolLogExportHandler implements ExportTypeHandler {
    private final EnterpriseAuthorizationService authorization;
    private final ToolLogService tools;
    private final ToolLogMapper records;
    private final ExportDefinitionMapper definitions;
    private final QueryTimeRangeService ranges;
    private final CsvExportMapper csv;

    public ToolLogExportHandler(EnterpriseAuthorizationService authorization, ToolLogService tools, ToolLogMapper records,
                                 ExportDefinitionMapper definitions, QueryTimeRangeService ranges, CsvExportMapper csv) {
        this.authorization = authorization;
        this.tools = tools;
        this.records = records;
        this.definitions = definitions;
        this.ranges = ranges;
        this.csv = csv;
    }

    @Override
    public String type() {
        return "tool_calls";
    }

    @Override
    public ExportDefinition validate(AuthContext actor, ExportDefinition definition) {
        var input = definitions.parameter(definition, "toolCalls", ToolLogQuery.class);
        if (input == null || input.from() == null || input.to() == null) {
            throw ApiException.invalidField("from", Messages.text("error.export.toolLogExportHandler.validate.1"));
        }
        var filter = tools.validate(input);
        ranges.resolve(filter.from(), filter.to(), null);
        return definitions.create(type(), "toolCalls", filter);
    }

    @Override
    public List<DataScope> authorize(AuthContext actor, ExportDefinition definition, boolean mutation) {
        var export = mutation ? authorization.lockAndRequire(actor, "tool_log.export")
            : authorization.require(actor, "tool_log.export");
        return List.of(authorization.require(actor, "tool_log.view"), export);
    }

    @Override
    public ExportSnapshot read(AuthContext actor, ExportDefinition definition, List<DataScope> scopes, Instant when) {
        var filter = definitions.parameter(definition, "toolCalls", ToolLogQuery.class);
        var range = ranges.resolve(filter.from(), filter.to(), null);
        var output = csv.rows(Messages.text("text.export.conversationExportHandler.read.2"), Messages.text("text.export.toolLogExportHandler.read.1"), Messages.text("text.workflow.workflowDraftMapper.schema.3"), Messages.text("text.export.toolLogExportHandler.read.2"), Messages.text("text.export.toolLogExportHandler.read.3"), Messages.text("text.export.toolLogExportHandler.read.4"), Messages.text("text.export.toolLogExportHandler.read.5"), Messages.text("text.export.toolLogExportHandler.read.6"));
        for (var entry : records.list(actor, scopes, null, filter, range, null, CsvExportMapper.MAX_ROWS)) {
            var row = entry.call();
            output.row(row.actor().id(), row.createdAt(), row.actor().displayName(), row.toolName(), row.source(),
                row.status(), row.operationClass(), row.durationMs() == null ? null : row.durationMs().toString(), row.errorSummary());
        }
        return output.finish(definition, when, Messages.text("text.export.toolLogExportHandler.read.7"));
    }
}
