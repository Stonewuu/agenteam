package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeInputOptionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.skill.SkillCandidateMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话使用固定员工版本，技能和资料候选均按本次固定能力及当前授权读取。
 */
@Service
public class AgentInputOptionsService {
    private final ExecutionConfigurationService configurations;
    private final ConversationMapper conversations;
    private final SkillCandidateMapper candidates;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceAuthorizationService access;
    private final ListPagination pagination;
    private final ResourceJson json;
    private final KnowledgeInputOptionMapper documents;

    public AgentInputOptionsService(ExecutionConfigurationService configurations, ConversationMapper conversations,
                                    SkillCandidateMapper candidates,
                                    EnterpriseAuthorizationService authorization, ResourceAuthorizationService access,
                                    ListPagination pagination, ResourceJson json,
                                    KnowledgeInputOptionMapper documents) {
        this.configurations = configurations;
        this.conversations = conversations;
        this.candidates = candidates;
        this.authorization = authorization;
        this.access = access;
        this.pagination = pagination;
        this.json = json;
        this.documents = documents;
    }

    public PageResponse<?> list(AuthContext actor, String agentId, String conversationId, String kind, String query,
                                String cursor, Integer requested) {
        authorization.require(actor, "agent.run");
        if (!List.of("skill", "document").contains(kind)) {
            throw ApiException.invalidField("kind", "请选择技能或资料。");
        }
        String fixedVersion = null;
        if (conversationId != null) {
            var conversation = conversations.find(actor.enterpriseId(), actor.userId(), conversationId, false)
                .orElseThrow(ResourceAuthorizationService::unavailable);
            if (!conversation.agentId().equals(agentId) || !conversation.mode().equals("normal")) {
                throw ResourceAuthorizationService.unavailable();
            }
            RunSubmissionService.active(conversation);
            fixedVersion = conversation.agentVersionId();
        }
        var selection = configurations.inputOptions(actor, agentId, fixedVersion);
        String search = pagination.query(query);
        int limit = pagination.limit(requested);
        if (kind.equals("document")) {
            var scope = access.usageScope(actor, "knowledge");
            var allowed = new LinkedHashMap<String, String>();
            for (var dependency : selection.dependencies()) {
                if (dependency.path("kind").asText().equals("knowledge")) {
                    allowed.putIfAbsent(dependency.path("resourceId").asText(), dependency.path("name").asText());
                }
            }
            var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "input-documents",
                json.hash(json.tree(Map.of("agentId", agentId,
                    "versionId", selection.versionId(), "conversationId", conversationId == null ? "" : conversationId,
                    "query", search))), "updated_desc");
            var rows = documents.list(scope, allowed, search, pagination.read(cursor, binding), limit);
            var page = pagination.page(rows, limit, binding,
                row -> new PagePosition(row.updatedAt(), row.option().documentId()));
            return new PageResponse<>(page.items().stream().map(KnowledgeInputOptionMapper.Candidate::option).toList(),
                page.nextCursor(), page.hasMore());
        }
        var scope = access.usageScope(actor, "skill");
        var allowed = new ArrayList<String>();
        selection.config().path("skillVersionIds").forEach(id -> allowed.add(id.asText()));
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "input-skills",
            json.hash(json.tree(Map.of("agentId", agentId, "versionId", selection.versionId(), "conversationId",
                conversationId == null ? "" : conversationId, "query", search))), "published_desc");
        var rows = candidates.input(scope, allowed, search, pagination.read(cursor, binding), limit);
        var page = pagination.page(rows, limit, binding,
            row -> new PagePosition(row.publishedAt(), row.option().versionId()));
        return new PageResponse<>(page.items().stream().map(SkillCandidateMapper.Candidate::option).toList(),
            page.nextCursor(), page.hasMore());
    }
}
