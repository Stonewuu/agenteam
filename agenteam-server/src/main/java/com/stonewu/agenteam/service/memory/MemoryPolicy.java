package com.stonewu.agenteam.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.memory.MemoryMapper;
import com.stonewu.agenteam.mapper.memory.MemorySourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.memory.response.MemoryContextView;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理本人已有偏好不依赖资源维护权；新增仍须具备真实员工使用资格。
 */
@Service
public class MemoryPolicy {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final AgentHireMapper hires;
    private final ResourceAuthorizationService access;
    private final UserPreferenceMapper preferences;
    private final MemorySourceMapper sources;
    private final RunMapper runs;
    private final ConversationQueryService conversations;
    private final MemoryMapper memories;

    public MemoryPolicy(AuthMapper users, EnterpriseMapper enterprises, ResourceMapper resources,
                        ResourceVersionMapper versions, AgentHireMapper hires,
                        ResourceAuthorizationService access, UserPreferenceMapper preferences,
                        MemorySourceMapper sources, RunMapper runs, ConversationQueryService conversations,
                        MemoryMapper memories) {
        this.users = users;
        this.enterprises = enterprises;
        this.resources = resources;
        this.versions = versions;
        this.hires = hires;
        this.access = access;
        this.preferences = preferences;
        this.sources = sources;
        this.runs = runs;
        this.conversations = conversations;
        this.memories = memories;
    }

    public void requireMember(AuthContext actor) {
        var current = users.findById(actor.userId()).orElseThrow(ResourceAuthorizationService::unavailable);
        if (!current.status().equals("active") || current.sessionVersion() != actor.user().sessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "登录已失效，请重新登录。");
        }
        if (!users.isActiveMember(actor.userId(), actor.enterpriseId())) {
            throw ResourceAuthorizationService.unavailable();
        }
    }

    public void mutation(AuthContext actor, String agent) {
        enterprises.lockEnterprise(actor.enterpriseId()).filter("active"::equals)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        // 先锁企业再锁用户，与个人开关修改串行提交，不能在开关关闭后继续新增。
        users.findByIdForUpdate(actor.userId(), 2).orElseThrow(ResourceAuthorizationService::unavailable);
        requireMember(actor);
        agent(actor, agent);
    }

    public ResourceRecord agent(AuthContext actor, String id) {
        requireMember(actor);
        return resources.find(actor.enterpriseId(), id, true, false).filter(value -> value.kind() == ResourceKind.AGENT)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public MemoryContextView context(AuthContext actor, String id, String sourceMessage) {
        var resource = agent(actor, id);
        boolean enabled = preferences.find(actor.userId()).orElseThrow().memoryEnabled();
        JsonNode config;
        try {
            config = configuration(actor, resource, sourceMessage);
        } catch (ResponseStatusException unavailable) {
            if (sourceMessage != null || memories.count(actor.enterpriseId(), actor.userId(), id) == 0 || !List.of(403,
                404, 409).contains(unavailable.getStatusCode().value())) {
                throw unavailable;
            }
            var appearance = resource.publishedVersionId() == null ? resource.config()
                : versions.find(actor.enterpriseId(), resource.publishedVersionId()).map(value -> value.config())
                .orElse(resource.config());
            return new MemoryContextView(id, resource.name(), enabled, false, List.of(),
                "该员工暂不可使用，仍可管理已经保存的偏好。",
                appearance.path("icon").asText(null), appearance.path("color").asText(null));
        }
        List<String> topics = new ArrayList<>();
        config.path("memoryFields").forEach(value -> topics.add(value.asText()));
        boolean allowed = config.path("memoryEnabled").asBoolean() && !topics.isEmpty();
        String reason = !enabled ? "请先在个人设置中开启记忆。" : !allowed ? "这位员工没有开放可保存的偏好。" : null;
        return new MemoryContextView(id, resource.name(), enabled, enabled && allowed,
            allowed ? List.copyOf(topics) : List.of(), reason,
            config.path("icon").asText(null), config.path("color").asText(null));
    }

    public void create(AuthContext actor, String agent, String sourceMessage, String topic) {
        var context = context(actor, agent, sourceMessage);
        if (!context.canSave()) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_DISABLED", context.unavailableReason());
        }
        if (!context.allowedTopics().contains(topic)) {
            throw ApiException.invalidField("memoryKey", "请选择该员工允许保存的偏好主题。");
        }
    }

    private JsonNode configuration(AuthContext actor, ResourceRecord resource, String message) {
        access.requireUse(actor, resource.id(), "agent");
        hires.forAgent(actor.enterpriseId(), actor.userId(), resource.id(), false)
            .filter(value -> value.status().equals("active")).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE", "请先雇佣或恢复使用这位员工。"));
        if (message != null) {
            var source = sources.find(actor.enterpriseId(), actor.userId(), resource.id(), message)
                .orElseThrow(ResourceAuthorizationService::unavailable);
            if (!conversations.canRead(actor, source.conversationId())) {
                throw ResourceAuthorizationService.unavailable();
            }
            var run = runs.find(actor.enterpriseId(), source.runId(), false).filter(
                value -> value.userId().equals(actor.userId()) && value.conversationId()
                    .equals(source.conversationId())).orElseThrow(ResourceAuthorizationService::unavailable);
            if (run.agentVersionId() != null) {
                versions.find(actor.enterpriseId(), run.agentVersionId())
                    .filter(value -> value.resourceId().equals(resource.id()) && value.status().equals("available"))
                    .orElseThrow(ResourceAuthorizationService::unavailable);
            }
            return run.executionConfig().path("config");
        }
        if (resource.publishedVersionId() == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return versions.find(actor.enterpriseId(), resource.publishedVersionId())
            .filter(value -> value.resourceId().equals(resource.id()) && value.status().equals("available"))
            .orElseThrow(ResourceAuthorizationService::unavailable).config();
    }
}
