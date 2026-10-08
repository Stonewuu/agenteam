package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.mapper.export.ConversationExportMapper;
import com.stonewu.agenteam.mapper.export.CsvExportMapper;
import com.stonewu.agenteam.mapper.export.ExportDefinitionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 私有对话导出仍只允许当前用户读取自己可访问的对话。 */
@Component
public class ConversationExportHandler implements ExportTypeHandler {
    private final EnterpriseAuthorizationService authorization;
    private final ConversationQueryService conversations;
    private final ConversationExportMapper records;
    private final ExportDefinitionMapper definitions;
    private final CsvExportMapper csv;

    public ConversationExportHandler(EnterpriseAuthorizationService authorization, ConversationQueryService conversations,
                                      ConversationExportMapper records, ExportDefinitionMapper definitions, CsvExportMapper csv) {
        this.authorization = authorization;
        this.conversations = conversations;
        this.records = records;
        this.definitions = definitions;
        this.csv = csv;
    }

    @Override
    public String type() {
        return "conversation";
    }

    @Override
    public ExportDefinition validate(AuthContext actor, ExportDefinition definition) {
        String id = conversations.readable(actor, conversationId(definition)).id();
        return definitions.create(type(), "conversationId", id);
    }

    @Override
    public List<DataScope> authorize(AuthContext actor, ExportDefinition definition, boolean mutation) {
        if (mutation) {
            authorization.lockAndRequire(actor, "conversation.export");
        } else {
            authorization.require(actor, "conversation.export");
        }
        authorization.require(actor, "conversation.view");
        conversations.readable(actor, conversationId(definition));
        return List.of(DataScope.OWN);
    }

    @Override
    public ExportSnapshot read(AuthContext actor, ExportDefinition definition, List<DataScope> scopes, Instant when) {
        var output = csv.rows("消息编号", "时间", "角色", "尝试", "消息状态", "内容编号", "父内容编号",
            "内容类型", "说明", "正文", "工具输入", "工具结果");
        records.append(actor.enterpriseId(), actor.userId(), conversationId(definition), output);
        return output.finish(definition, when, "对话记录.csv");
    }

    private String conversationId(ExportDefinition definition) {
        String id = definitions.parameter(definition, "conversationId", String.class);
        if (id == null || id.isBlank()) {
            throw ExportAccessService.unavailable();
        }
        return id;
    }
}
