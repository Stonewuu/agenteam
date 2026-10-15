package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 成果保存为不可变版本，摘要与完整内容分开，精确计算必须读取原成果。 */
@Service
public class CollaborationArtifactService {
    private final CollaborationArtifactMapper artifacts;
    private final CollaborationWorkItemMapper works;
    private final CollaborationAccessService access;
    private final FileAccessService fileAccess;
    private final FileMapper files;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;

    public CollaborationArtifactService(CollaborationArtifactMapper artifacts, CollaborationWorkItemMapper works,
            CollaborationAccessService access, FileAccessService fileAccess, FileMapper files,
            ChatPayloadCipher cipher, ResourceJson json, Clock clock) {
        this.artifacts = artifacts;
        this.works = works;
        this.access = access;
        this.fileAccess = fileAccess;
        this.files = files;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CollaborationArtifactRow publish(CollaborationTaskRow task, CollaborationWorkItemRow work, String run,
            String key, String title, String summary, String kind, JsonNode content, String fileId, String schema) {
        ResourceInput.text(key, "artifactKey", 100, true);
        ResourceInput.text(title, "title", 160, true);
        ResourceInput.text(summary, "summary", 2000, true);
        if (!Set.of("text", "json", "file").contains(kind)) {
            throw ApiException.invalidField("kind", "请选择文字、结构化数据或文件成果。");
        }
        if (schema != null) {
            ResourceInput.text(schema, "schemaName", 80, false);
        }
        var actor = access.requireArtifactRole(task, access.participant(task, work.getParticipantId()));
        String hash;
        if (kind.equals("file")) {
            var file = fileAccess.ready(actor, fileId);
            if (run == null || !run.equals(file.runId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ARTIFACT_ACCESS_DENIED", "只能发布本岗位当前执行已经取得的文件成果。");
            }
            hash = file.sha256();
            files.retain(file, clock.instant());
        } else {
            if (fileId != null || content == null || content.isNull() || kind.equals("text") && !content.isTextual()) {
                throw ApiException.invalidField("content", "请提供与成果类型一致的完整内容。");
            }
            if (content.toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                throw ApiException.invalidField("content", "成果内容超过允许长度，请保存为文件后发布。");
            }
            hash = json.hash(content);
        }
        var previous = artifacts.selectPage(new Page<CollaborationArtifactRow>(1, 1, false),
            new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getArtifactKey, key)
                .orderByDesc(CollaborationArtifactRow::getArtifactVersion)).getRecords();
        if (!previous.isEmpty()) {
            var producer = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationWorkItemRow::getId, previous.getFirst().getProducerWorkId()).eq(CollaborationWorkItemRow::getTaskId, task.getId()));
            if (producer == null || !producer.getParticipantId().equals(work.getParticipantId())) {
                throw ApiException.invalidField("artifactKey", "该成果名称已由其他岗位使用，请选择自己的成果名称。");
            }
        }
        var row = ChatRecords.initialize(new CollaborationArtifactRow(), task.getEnterpriseId(), clock.instant());
        row.setTaskId(task.getId());
        row.setProducerWorkId(work.getId());
        row.setProducerRunId(run);
        row.setArtifactKey(key);
        row.setArtifactVersion(previous.isEmpty() ? 1 : previous.getFirst().getArtifactVersion() + 1);
        row.setKind(kind);
        row.setSchemaName(schema);
        row.setTitle(title);
        row.setSummaryEncrypted(cipher.encrypt(task.getEnterpriseId(), "artifact-summary", row.getId(), Map.of("text", summary)));
        row.setContentEncrypted(content == null ? null : cipher.encrypt(task.getEnterpriseId(), "artifact-content", row.getId(), content));
        row.setFileId(kind.equals("file") ? fileId : null);
        row.setSha256(hash);
        row.setInputRevision(work.getInputRevision());
        row.setVisibility("task_private");
        row.setStatus("ready");
        row.setSupersedesId(previous.isEmpty() ? null : previous.getFirst().getId());
        artifacts.insert(row);
        return row;
    }

    public CollaborationArtifactRow require(CollaborationTaskRow task, String id, int version) {
        var row = artifacts.selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getId, id)
            .eq(CollaborationArtifactRow::getArtifactVersion, version).eq(CollaborationArtifactRow::getStatus, "ready"));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_ACCESS_DENIED", "成果不存在或不能用于当前任务。");
        }
        var producer = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getId, row.getProducerWorkId()));
        if (producer == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_ACCESS_DENIED", "成果来源已不可用。");
        }
        access.requireArtifactRole(task, access.participant(task, producer.getParticipantId()));
        if (row.getFileId() != null) {
            var file = fileAccess.ready(access.requireArtifactRole(task, access.participant(task, producer.getParticipantId())), row.getFileId());
            if (!file.sha256().equals(row.getSha256())) {
                throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_CONTENT_CHANGED", "成果文件已发生变化，不能继续使用旧引用。");
            }
        }
        return row;
    }

    public List<CollaborationArtifactRow> references(CollaborationTaskRow task, JsonNode references) {
        if (!references.isArray() || references.size() > 20) {
            throw ApiException.invalidField("artifactRefs", "请选择最多二十项固定版本成果。");
        }
        var result = new ArrayList<CollaborationArtifactRow>();
        for (var value : references) {
            if (!value.isObject() || value.size() != 2 || !value.path("artifactId").isTextual() || !value.path("version").canConvertToInt()) {
                throw ApiException.invalidField("artifactRefs", "成果引用必须包含成果编号和版本。");
            }
            result.add(require(task, value.path("artifactId").asText(), value.path("version").asInt()));
        }
        return List.copyOf(result);
    }

    public JsonNode describe(CollaborationArtifactRow artifact) {
        return json.tree(Map.of("id", artifact.getId(), "version", artifact.getArtifactVersion(), "kind", artifact.getKind(),
            "title", artifact.getTitle(), "summary", cipher.decrypt(artifact.getEnterpriseId(), "artifact-summary", artifact.getId(), artifact.getSummaryEncrypted()).path("text").asText(),
            "sha256", artifact.getSha256(), "inputRevision", artifact.getInputRevision(), "summaryOnly", "collaboration_result_v1".equals(artifact.getSchemaName())));
    }

    public List<CollaborationArtifactRow> workResults(CollaborationTaskRow task, CollaborationWorkItemRow work) {
        if (work.getResultArtifactId() == null) {
            return List.of();
        }
        var result = artifacts.selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getProducerWorkId, work.getId())
            .eq(CollaborationArtifactRow::getId, work.getResultArtifactId()).eq(CollaborationArtifactRow::getStatus, "ready"));
        if (result == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_ACCESS_DENIED", "依赖工作的成果已不能读取。");
        }
        var included = new ArrayList<CollaborationArtifactRow>();
        included.add(result);
        if ("collaboration_result_v1".equals(result.getSchemaName())) {
            var body = cipher.decrypt(task.getEnterpriseId(), "artifact-content", result.getId(), result.getContentEncrypted());
            included.addAll(references(task, body.path("artifactRefs")));
        }
        return List.copyOf(included);
    }

    public JsonNode read(CollaborationTaskRow task, String id, int version, int offset, int limit) {
        var row = require(task, id, version);
        if (offset < 0 || limit < 1 || limit > 16000) {
            throw ApiException.invalidField("offset", "读取范围不正确，每次最多一万六千个字符。");
        }
        if (row.getFileId() != null) {
            return json.tree(Map.of("artifact", describe(row), "fileId", row.getFileId()));
        }
        var content = cipher.decrypt(task.getEnterpriseId(), "artifact-content", row.getId(), row.getContentEncrypted());
        String text = content.isTextual() ? content.asText() : content.toString();
        int size = text.codePointCount(0, text.length());
        int from = Math.min(offset, size);
        int to = from;
        int bytes = 0;
        while (to < Math.min(size, from + limit)) {
            int start = text.offsetByCodePoints(0, to);
            int end = start + Character.charCount(text.codePointAt(start));
            int next = text.substring(start, end).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + next > 8000) {
                break;
            }
            bytes += next;
            to++;
        }
        return json.tree(Map.of("artifact", describe(row), "text", text.substring(text.offsetByCodePoints(0, from), text.offsetByCodePoints(0, to)),
            "offset", from, "nextOffset", to, "hasMore", to < size));
    }
}
