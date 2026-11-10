package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomEntryFileMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryFileRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class WebRoomAttachments {
    private final CollaborationRoomEntryFileMapper entries;
    private final CollaborationWorkItemMapper works;
    private final FileAccessService files;
    private final CollaborationAccessService access;
    private final CollaborationInputArtifacts artifacts;
    private final WebRoomRoster roster;
    private final FileMapper storedFiles;
    private final MessageAttachmentSqlMapper fileRetention;
    private final Clock clock;

    public WebRoomAttachments(CollaborationRoomEntryFileMapper entries, CollaborationWorkItemMapper works, FileAccessService files,
            CollaborationAccessService access, CollaborationInputArtifacts artifacts, WebRoomRoster roster, FileMapper storedFiles, MessageAttachmentSqlMapper fileRetention, Clock clock) {
        this.storedFiles = storedFiles;
        this.fileRetention = fileRetention;
        this.clock = clock;
        this.entries = entries;
        this.works = works;
        this.files = files;
        this.access = access;
        this.artifacts = artifacts;
        this.roster = roster;
    }

    public static List<String> ids(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        if (input.size() > 10 || input.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 100)
            || new HashSet<>(input).size() != input.size()) {
            throw ApiException.invalidField("attachmentIds", "每条消息最多可选择十份不同的附件。");
        }
        return List.copyOf(input);
    }

    public boolean matches(CollaborationRoomEntryRow entry, List<String> ids) {
        return entries.fileIds(entry.getEnterpriseId(), entry.getId()).equals(ids);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(AuthContext actor, CollaborationTaskRow task, CollaborationRoomEntryRow entry, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var owner = access.participant(task, task.getOwnerParticipantId());
        access.requireRole(task, owner);
        register(task, store(actor, entry, owner.getAgentId(), owner.getAgentVersionId(), ids, false));
    }

    public void capture(AuthContext actor, CollaborationRoomEntryRow entry, String agent, String version, List<String> ids) {
        store(actor, entry, agent, version, ids, true);
    }

    private List<CollaborationInputArtifacts.InputFile> store(AuthContext actor, CollaborationRoomEntryRow entry, String agent,
            String version, List<String> ids, boolean waiting) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (!roster.acceptsFiles(actor.enterpriseId(), agent, version)) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENTS_DISABLED", "这位成员未启用接收附件，请移除附件或选择其他成员。");
        }
        var links = new ArrayList<CollaborationRoomEntryFileRow>();
        var inputs = new ArrayList<CollaborationInputArtifacts.InputFile>();
        long bytes = 0;
        for (String id : ids) {
            var file = files.uploadOwner(actor, id, true);
            if (!"attachment".equals(file.purpose())) {
                throw FileAccessService.unavailable();
            }
            if (!"ready".equals(file.status()) || file.sha256() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "请等附件检查完成后再发送。");
            }
            bytes += file.sizeBytes();
            if (file.sizeBytes() > 20L * 1024 * 1024 || bytes > 50L * 1024 * 1024) {
                throw ApiException.invalidField("attachmentIds", "每份附件不能超过 20 MB，本次附件合计不能超过 50 MB。");
            }
            var link = new CollaborationRoomEntryFileRow();
            link.setId(UUID.randomUUID().toString());
            link.setEnterpriseId(actor.enterpriseId());
            link.setRoomId(entry.getRoomId());
            link.setEntryId(entry.getId());
            link.setFileId(id);
            link.setOrdinal(links.size());
            link.setSha256(file.sha256());
            links.add(link);
            inputs.add(new CollaborationInputArtifacts.InputFile("web_input:" + link.getId(), file));
            if (waiting) {
                storedFiles.retain(file, clock.instant());
            }
        }
        entries.insert(links);
        return inputs;
    }

    public void publish(AuthContext actor, CollaborationTaskRow task, CollaborationRoomEntryRow entry) {
        var links = entries.selectList(new LambdaQueryWrapper<CollaborationRoomEntryFileRow>()
            .eq(CollaborationRoomEntryFileRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationRoomEntryFileRow::getRoomId, task.getWebRoomId())
            .eq(CollaborationRoomEntryFileRow::getEntryId, entry.getId()).orderByAsc(CollaborationRoomEntryFileRow::getOrdinal));
        if (links.isEmpty()) {
            return;
        }
        var owner = access.participant(task, task.getOwnerParticipantId());
        if (!roster.acceptsFiles(actor.enterpriseId(), owner.getAgentId(), owner.getAgentVersionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENTS_DISABLED", "这位成员未启用接收附件。");
        }
        var inputs = new ArrayList<CollaborationInputArtifacts.InputFile>();
        for (var link : links) {
            var file = files.uploadOwner(actor, link.getFileId(), true);
            if (!"ready".equals(file.status()) || !link.getSha256().equals(file.sha256())) {
                throw FileAccessService.unavailable();
            }
            inputs.add(new CollaborationInputArtifacts.InputFile("web_input:" + link.getId(), file));
        }
        register(task, inputs);
    }

    private void register(CollaborationTaskRow task, List<CollaborationInputArtifacts.InputFile> inputs) {
        var work = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getId, task.getOwnerWorkId()));
        artifacts.register(task, work, inputs, "web_message_attachment_v1", "发起人在这条消息中提供的附件。");
    }

    public void withdraw(CollaborationRoomEntryRow entry) {
        var ids = entries.fileIds(entry.getEnterpriseId(), entry.getId());
        if (!ids.isEmpty()) {
            entries.delete(new LambdaQueryWrapper<CollaborationRoomEntryFileRow>()
                .eq(CollaborationRoomEntryFileRow::getEnterpriseId, entry.getEnterpriseId())
                .eq(CollaborationRoomEntryFileRow::getRoomId, entry.getRoomId()).eq(CollaborationRoomEntryFileRow::getEntryId, entry.getId()));
            fileRetention.expireDetachedFiles(entry.getEnterpriseId(), ids, clock.instant());
        }
    }
}
