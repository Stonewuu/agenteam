package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.data.DataApiService;
import com.stonewu.agenteam.service.data.DataQueryService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.knowledge.KnowledgeReferenceService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 每次执行步骤重新核对已读取资料，避免恢复或后续模型请求继续使用已撤权的缓存结果。
 */
@Service
public class SourceToolResultAccessService {
    private final ToolCallMapper calls;
    private final DataQueryService data;
    private final KnowledgeReferenceService knowledge;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final PayloadEncryption encryption;
    private final ObjectProvider<FileAccessService> files;

    public SourceToolResultAccessService(ToolCallMapper calls, DataQueryService data,
                                         KnowledgeReferenceService knowledge,
                                         ResourceVersionMapper versions, ResourceAuthorizationService access,
                                         PayloadEncryption encryption, ObjectProvider<FileAccessService> files) {
        this.calls = calls;
        this.data = data;
        this.knowledge = knowledge;
        this.versions = versions;
        this.access = access;
        this.encryption = encryption;
        this.files = files;
    }

    public void requireCurrent(AuthContext actor, RunRecord run) {
        authorizeAll(actor, calls.sourceResultsForContext(run));
    }

    public void requireResult(AuthContext actor, ToolCallRecord call) {
        authorizeAll(actor, List.of(call));
    }

    private void authorizeAll(AuthContext actor, List<ToolCallRecord> initial) {
        var known = new LinkedHashMap<String, ToolCallRecord>();
        initial.forEach(call -> known.put(call.id(), call));
        var pending = new ArrayList<>(initial);
        while (!pending.isEmpty()) {
            var ids = new LinkedHashSet<String>();
            for (var call : pending) {
                if (!actor.enterpriseId().equals(call.enterpriseId()) || !actor.userId().equals(call.actorUserId())) {
                    throw ResourceAuthorizationService.unavailable();
                }
                if (workspace(call) && call.resultRedacted() != null) {
                    for (var id : call.resultRedacted().path("sourceResultIds")) {
                        if (!known.containsKey(id.asText())) {
                            ids.add(id.asText());
                        }
                    }
                }
            }
            pending.clear();
            var requested = new ArrayList<>(ids);
            for (int start = 0; start < requested.size(); start += 100) {
                var batch = requested.subList(start, Math.min(requested.size(), start + 100));
                var found = calls.findMany(actor.enterpriseId(), batch);
                if (found.size() != batch.size()) {
                    throw ResourceAuthorizationService.unavailable();
                }
                found.forEach(call -> known.put(call.id(), call));
                pending.addAll(found);
            }
        }
        var inputIds = new LinkedHashSet<String>();
        for (var call : known.values()) {
            authorizeOne(actor, call);
            if (workspace(call) && call.resultRedacted() != null) {
                call.resultRedacted().path("inputFileIds").forEach(id -> inputIds.add(id.asText()));
            }
        }
        for (String id : inputIds) {
            files.getObject().ready(actor, id);
        }
    }

    private boolean workspace(ToolCallRecord call) {
        return Set.of("agent", "workflow").contains(call.resourceKind());
    }

    private void authorizeOne(AuthContext actor, ToolCallRecord call) {
        // 预览执行的主资源资格已由执行或对话读取入口检查，保存的资料来源仍逐项复核。
        if (workspace(call) && call.resourceVersionId() == null) {
            return;
        }
        access.requireUse(actor, call.resourceId(), call.resourceKind());
        versions.find(actor.enterpriseId(), call.resourceVersionId())
            .filter(value -> value.resourceId().equals(call.resourceId()) && value.status().equals("available"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        JsonNode result = call.resultRedacted();
        if (result == null) {
            return;
        }
        if (call.resourceKind().equals("knowledge")) {
            for (var citation : result.path("citations")) {
                knowledge.read(actor, citation.path("documentId").asText(), citation.path("generation").asInt());
            }
        } else if (call.resourceKind().equals("data") && call.toolName().equals("data_query")) {
            var raw = encryption.decrypt(call.requestEncrypted(),
                "tool-request:" + actor.enterpriseId() + ":" + call.id(), JsonNode.class).path("arguments");
            data.authorizeResult(actor, call.resourceId(), DataApiService.queryInput(raw),
                result.path(result.path("truncated").asBoolean() ? "dataFields" : "fields"));
        } else if (call.resourceKind().equals("data") && call.toolName().equals("data_collections")) {
            for (var collection : result.path("items")) {
                var names = new ArrayList<String>();
                collection.path("fields").forEach(field -> names.add(field.path("name").asText()));
                data.authorizeResult(actor, call.resourceId(),
                    new DataQueryRequest(collection.path("id").asText(), collection.path("activeGeneration").asInt(),
                        names, List.of(), List.of(), 1, 0), collection.path("fields"));
            }
        }
    }
}
