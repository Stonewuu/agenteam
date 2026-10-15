package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map;

/** 原发起人的平台附件登记为固定成果，再交给负责人本轮的文件工具。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationInputArtifacts {
    private final ChatMessageResourceMapper resources;
    private final CollaborationArtifactMapper artifacts;
    private final CollaborationWorkItemMapper works;
    private final FileMapper files;
    private final FileAccessService fileAccess;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;

    public CollaborationInputArtifacts(ChatMessageResourceMapper resources, CollaborationArtifactMapper artifacts,
            CollaborationWorkItemMapper works, FileMapper files, FileAccessService fileAccess, ChatPayloadCipher cipher, ResourceJson json, Clock clock) {
        this.resources = resources;
        this.artifacts = artifacts;
        this.works = works;
        this.files = files;
        this.fileAccess = fileAccess;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    public void register(AuthContext actor, CollaborationTaskRow task, CollaborationWorkItemRow work, String messageId) {
        var inputs = resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageResourceRow::getMessageId, messageId).orderByAsc(ChatMessageResourceRow::getResourceOrdinal));
        if (inputs.isEmpty()) {
            return;
        }
        var instruction = (ObjectNode) cipher.decrypt(actor.enterpriseId(), "work-instruction", work.getId(), work.getInstructionEncrypted());
        var references = instruction.withArray("artifactRefs");
        var existing = new HashSet<String>();
        references.forEach(reference -> existing.add(reference.path("artifactId").asText()));
        for (var resource : inputs) {
            if (!resource.getStatus().equals("ready") || resource.getFileId() == null) {
                throw ApiException.invalidField("messageReferences", "原消息的附件尚未完成检查。");
            }
            var file = fileAccess.ready(actor, resource.getFileId());
            if (!file.purpose().equals("collaboration_artifact") || !file.sha256().equals(resource.getSha256())) {
                throw ApiException.invalidField("messageReferences", "原附件的内容或用途已发生变化。");
            }
            String key = "input:" + resource.getId();
            var artifact = artifacts.selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getArtifactKey, key).eq(CollaborationArtifactRow::getArtifactVersion, 1));
            if (artifact == null) {
                artifact = ChatRecords.initialize(new CollaborationArtifactRow(), actor.enterpriseId(), clock.instant());
                artifact.setTaskId(task.getId());
                artifact.setProducerWorkId(work.getId());
                artifact.setArtifactKey(key);
                artifact.setArtifactVersion(1);
                artifact.setKind("file");
                artifact.setSchemaName("platform_message_attachment_v1");
                String name = file.originalName();
                artifact.setTitle(name.substring(0, name.offsetByCodePoints(0, Math.min(160, name.codePointCount(0, name.length())))));
                artifact.setSummaryEncrypted(cipher.encrypt(actor.enterpriseId(), "artifact-summary", artifact.getId(), Map.of("text", "原发起人通过平台消息提供的附件，请读取实际内容。")));
                artifact.setFileId(file.id());
                artifact.setSha256(file.sha256());
                artifact.setInputRevision(task.getInputRevision());
                artifact.setVisibility("task_private");
                artifact.setStatus("ready");
                artifacts.insert(artifact);
                files.retain(file, clock.instant());
            }
            if (existing.add(artifact.getId())) {
                references.addObject().put("artifactId", artifact.getId()).put("version", artifact.getArtifactVersion());
            }
        }
        if (references.size() > 20) {
            throw ApiException.invalidField("messageReferences", "本项工作引用的成果超过二十项，请拆分任务。");
        }
        if (works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getId, work.getId())
            .set(CollaborationWorkItemRow::getInstructionEncrypted, cipher.encrypt(actor.enterpriseId(), "work-instruction", work.getId(), instruction))) != 1) {
            throw new IllegalStateException("原附件无法加入负责人工作");
        }
    }
}
