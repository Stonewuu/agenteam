package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationContextMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只允许当前工作明确携带且已固定版本与摘要的文件进入现有附件工具。 */
@Service
public class CollaborationSourceAccess {
    private final CollaborationContextMapper contexts;
    private final CollaborationArtifactMapper artifacts;
    private final CollaborationRunLinkMapper links;
    private final CollaborationAccessService access;
    private final FileAccessService files;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;

    public CollaborationSourceAccess(CollaborationContextMapper contexts, CollaborationArtifactMapper artifacts, CollaborationRunLinkMapper links,
            CollaborationAccessService access, FileAccessService files, ChatPayloadCipher cipher, ResourceJson json) {
        this.contexts = contexts;
        this.artifacts = artifacts;
        this.links = links;
        this.access = access;
        this.files = files;
        this.cipher = cipher;
        this.json = json;
    }

    public List<FileRecord> files(AuthContext actor, JsonNode fixed) {
        if (!"collaboration".equals(fixed.path("sourceType").asText())) {
            return List.of();
        }
        var metadata = fixed.path("collaboration");
        var task = access.owned(actor, metadata.path("taskId").asText());
        var role = access.participant(task, metadata.path("participantId").asText());
        access.requireRole(task, role);
        var context = contexts.selectOne(new LambdaQueryWrapper<CollaborationContextRow>().eq(CollaborationContextRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationContextRow::getTaskId, task.getId()).eq(CollaborationContextRow::getWorkId, metadata.path("workItemId").asText())
            .eq(CollaborationContextRow::getId, metadata.path("contextId").asText()).eq(CollaborationContextRow::getRunRound, metadata.path("runRound").asInt())
            .eq(CollaborationContextRow::getInputRevision, task.getInputRevision()));
        if (context == null) {
            throw unavailable();
        }
        var content = cipher.decrypt(actor.enterpriseId(), "context", context.getId(), context.getContentEncrypted());
        Map<String, JsonNode> pinned = new HashMap<>();
        json.read(context.getSourceRefsJson()).forEach(reference -> pinned.put(reference.path("artifactId").asText(), reference));
        var requested = new HashMap<String, Integer>();
        var inputArtifacts = content.at("/assignment/inputArtifactRefs");
        if (inputArtifacts.isMissingNode()) {
            inputArtifacts = content.at("/assignment/instruction/artifactRefs");
        }
        for (var reference : inputArtifacts) {
            requested.put(reference.path("artifactId").asText(), reference.path("version").asInt());
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        var selected = artifacts.selectList(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, task.getId()).in(CollaborationArtifactRow::getId, requested.keySet()).eq(CollaborationArtifactRow::getStatus, "ready"));
        if (selected.size() != requested.size()) {
            throw unavailable();
        }
        var result = new ArrayList<FileRecord>();
        for (var artifact : selected) {
            var source = pinned.get(artifact.getId());
            if (source == null || !artifact.getArtifactVersion().equals(requested.get(artifact.getId()))
                || source.path("version").asInt() != artifact.getArtifactVersion() || !artifact.getSha256().equals(source.path("sha256").asText())) {
                throw unavailable();
            }
            if (artifact.getFileId() != null) {
                var file = files.ready(actor, artifact.getFileId());
                if (!actor.userId().equals(file.ownerUserId()) || !file.sha256().equals(artifact.getSha256())) {
                    throw unavailable();
                }
                result.add(file);
            }
        }
        return result.stream().distinct().toList();
    }

    public void requireCurrent(AuthContext actor, RunRecord run, FileRecord file) {
        if (filesForRun(actor, run).stream().noneMatch(value -> value.id().equals(file.id()) && value.sha256().equals(file.sha256()))) {
            throw unavailable();
        }
    }

    public List<FileRecord> filesForRun(AuthContext actor, RunRecord run) {
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRunLinkRow::getRunId, run.id()).eq(CollaborationRunLinkRow::getContextId, run.executionConfig().at("/collaboration/contextId").asText()));
        if (link == null) {
            throw unavailable();
        }
        return files(actor, run.executionConfig());
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "ARTIFACT_ACCESS_DENIED", "文件不在当前岗位已授权的固定成果范围内。");
    }
}
