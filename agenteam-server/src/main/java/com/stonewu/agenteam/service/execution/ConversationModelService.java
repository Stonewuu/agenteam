package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.response.ConversationModelOptions;
import com.stonewu.agenteam.model.execution.response.ConversationView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.modelprofile.AgentModelSelectionService;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;

/**
 * 对话模型选择与消息提交共用会话行锁，保存成功后才改变后续任务的参数。
 */
@Service
public class ConversationModelService {
    private final ConversationMapper conversations;
    private final ExecutionConfigurationService configurations;
    private final ModelProfileCatalog models;
    private final EnterpriseAuthorizationService authorization;
    private final ConversationQueryService queries;
    private final Clock clock;

    public ConversationModelService(ConversationMapper conversations, ExecutionConfigurationService configurations,
                                    ModelProfileCatalog models, EnterpriseAuthorizationService authorization,
                                    ConversationQueryService queries, Clock clock) {
        this.conversations = conversations;
        this.configurations = configurations;
        this.models = models;
        this.authorization = authorization;
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConversationModelOptions options(AuthContext actor, String agentId, String conversationId) {
        authorization.require(actor, "agent.run");
        var conversation = conversationId == null ? null : owned(actor, conversationId, false);
        if (conversation != null && !agentId.equals(conversation.agentId())) {
            throw ResourceAuthorizationService.unavailable();
        }
        var source = configurations.inputOptions(actor, agentId,
            conversation == null ? null : conversation.agentVersionId());
        var config = source.config();
        if ("workflow".equals(config.path("agentType").asText())) {
            return new ConversationModelOptions(false, null, null, List.of());
        }
        var defaults = ModelSelection.fromConfig(config);
        var selection = conversation == null || conversation.modelSelection() == null ? defaults : conversation.modelSelection();
        var options = models.list(actor).stream().map(model -> {
            String reason = model.enabled() ? AgentModelSelectionService.unavailableReason(config,
                model.capabilities()) : "已停用";
            return new ConversationModelOptions.Option(model.id(), model.name(), model.modelName(),
                model.capabilities().reasoningEfforts(), reason == null, reason);
        }).toList();
        return new ConversationModelOptions(true, defaults, selection, options);
    }

    public ConversationRecord authorize(AuthContext actor, String id, boolean lock) {
        if (lock) {
            authorization.lockAndRequire(actor, "agent.run");
        } else {
            authorization.require(actor, "agent.run");
        }
        return owned(actor, id, lock);
    }

    @Transactional
    public ConversationView select(AuthContext actor, String id, ModelSelection selection, long revision) {
        InputValidation.validate(selection);
        var conversation = authorize(actor, id, true);
        RunSubmissionService.active(conversation);
        if (conversation.revision() != revision) {
            throw ApiException.versionConflict(conversation.revision());
        }
        if (conversation.activeRunId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                "请等待当前回复结束，或先停止任务，再切换模型或思考等级。");
        }
        configurations.normal(actor, conversation.agentId(), conversation.agentVersionId(), selection);
        if (!selection.equals(conversation.modelSelection())) {
            conversations.selectModel(conversation, selection, clock.instant());
        }
        return queries.view(actor, owned(actor, id, false), new HashMap<>());
    }

    private ConversationRecord owned(AuthContext actor, String id, boolean lock) {
        return conversations.find(actor.enterpriseId(), actor.userId(), id, lock)
            .filter(row -> !"deleted".equals(row.status()) && "normal".equals(row.mode()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }
}
