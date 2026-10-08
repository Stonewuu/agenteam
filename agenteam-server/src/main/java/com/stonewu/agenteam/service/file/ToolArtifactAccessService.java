package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import com.stonewu.agenteam.service.tool.SourceToolResultAccessService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * 工具附件属于原操作者；对话、来源版本及资源使用资格仍需同时有效。
 */
@Service
public class ToolArtifactAccessService {
    private final RunMapper runs;
    private final ConversationQueryService conversations;
    private final ResourceAuthorizationService resources;
    private final ResourceVersionMapper versions;
    private final ResourceMapper definitions;
    private final ToolCallMapper calls;
    private final SourceToolResultAccessService sourceResults;
    private final ResourcePolicy policy;

    public ToolArtifactAccessService(RunMapper runs, ConversationQueryService conversations,
                                     ResourceAuthorizationService resources, ResourceVersionMapper versions,
                                     ResourceMapper definitions, ToolCallMapper calls,
                                     SourceToolResultAccessService sourceResults, ResourcePolicy policy) {
        this.runs = runs;
        this.conversations = conversations;
        this.resources = resources;
        this.versions = versions;
        this.definitions = definitions;
        this.calls = calls;
        this.sourceResults = sourceResults;
        this.policy = policy;
    }

    public void require(AuthContext actor, FileRecord file) {
        if (file.runId() == null || file.resourceId() == null) {
            throw FileAccessService.unavailable();
        }
        var run = runs.find(actor.enterpriseId(), file.runId(), false)
            .filter(value -> value.userId().equals(actor.userId())).orElseThrow(FileAccessService::unavailable);
        conversations.readable(actor, run.conversationId());
        var source = definitions.find(actor.enterpriseId(), file.resourceId(), false, false)
            .orElseThrow(FileAccessService::unavailable);
        if (Set.of("agent", "workflow").contains(source.kind().code())) {
            var call = calls.forArtifact(actor.enterpriseId(), run.id(), file.resourceId(), file.id())
                .filter(
                    value -> Objects.equals(value.resourceVersionId(), file.resourceVersionId()) && value.actorUserId()
                        .equals(actor.userId()))
                .orElseThrow(FileAccessService::unavailable);
            if (file.resourceVersionId() == null) {
                if (!run.mode().equals("preview") || !run.executionConfig().path(source.kind().code() + "Id").asText()
                    .equals(file.resourceId())) {
                    throw FileAccessService.unavailable();
                }
                policy.authorize(actor, file.resourceId(), "preview", false, false);
            }
            sourceResults.requireResult(actor, call);
            return;
        }
        if (file.resourceVersionId() == null) {
            throw FileAccessService.unavailable();
        }
        versions.find(actor.enterpriseId(), file.resourceVersionId())
            .filter(value -> value.resourceId().equals(file.resourceId()) && value.status().equals("available"))
            .orElseThrow(FileAccessService::unavailable);
        resources.requireUse(actor, file.resourceId(), source.kind().code());
        if (source.kind().code().equals("data") || source.kind().code().equals("knowledge")) {
            var call = calls.forArtifact(actor.enterpriseId(), run.id(), file.resourceId(), file.id())
                .filter(value -> value.resourceVersionId().equals(file.resourceVersionId()))
                .orElseThrow(FileAccessService::unavailable);
            sourceResults.requireResult(actor, call);
        } else if (!source.kind().code().equals("plugin")) {
            throw FileAccessService.unavailable();
        }
    }
}
