package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileTextMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.knowledge.KnowledgeReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

/**
 * 输入资料在提交时固定正文，执行时再次校验原文件及知识引用的访问资格。
 */
@Service
public class ExecutionSourceService {
    private final FileAccessService access;
    private final FileMapper files;
    private final FileTextMapper texts;
    private final MessageAttachmentMapper attachments;
    private final KnowledgeReferenceService references;
    private final ObjectMapper json;
    private final Clock clock;

    public ExecutionSourceService(FileAccessService access, FileMapper files, FileTextMapper texts,
                                  MessageAttachmentMapper attachments,
                                  KnowledgeReferenceService references, ObjectMapper json, Clock clock) {
        this.access = access;
        this.files = files;
        this.texts = texts;
        this.attachments = attachments;
        this.references = references;
        this.json = json;
        this.clock = clock;
    }

    public List<ContentBlock> freeze(AuthContext actor, ObjectNode fixed, MessageInput input) {
        if (!input.attachmentIds().isEmpty() && !fixed.path("config").path("attachmentsEnabled").asBoolean()) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENTS_DISABLED",
                "这个员工暂不接收附件，请移除附件后再发送。");
        }
        long bytes = 0;
        int remaining = 50000;
        StringBuilder content = new StringBuilder();
        var selected = fixed.putArray("inputSourceFiles");
        for (String id : input.attachmentIds()) {
            var file = access.uploadOwner(actor, id, true);
            if (!file.purpose().equals("attachment")) {
                throw FileAccessService.unavailable();
            }
            if (!file.status().equals("ready")) {
                throw notReady();
            }
            bytes += file.sizeBytes();
            if (file.sizeBytes() > 20L * 1024 * 1024 || bytes > 50L * 1024 * 1024) {
                throw ApiException.invalidField("attachmentIds", "单个附件不能超过二十 MiB，全部附件不能超过五十 MiB。");
            }
            var text = texts.find(file).orElseGet(() -> {
                if (file.mediaType().equals("application/pdf") && "FILE_NO_TEXT".equals(file.errorCode())) {
                    return new AttachmentText("", false);
                }
                throw notReady();
            });
            selected.addObject().put("id", file.id()).put("sha256", file.sha256());
            String part = take(text.text(), remaining);
            remaining -= part.codePointCount(0, part.length());
            content.append("\n\n附件：").append(file.originalName()).append('\n').append(part);
            if (text.text().isBlank()) {
                content.append("（此附件未提取到文字。请先读取原文件，不能根据文件名推测或编造内容。）");
            }
            if (part.length() < text.text().length() || text.truncated()) {
                content.append("\n（附件文字只提供了受限部分。）");
            }
        }
        var chosen = fixed.putArray("inputKnowledgeReferences");
        var blocks = new ArrayList<ContentBlock>();
        var unique = new HashSet<String>();
        int knowledgeRemaining = 8000;
        var allowedKnowledge = new HashSet<String>();
        for (var dependency : fixed.path("dependencies")) {
            if (dependency.path("kind").asText().equals("knowledge")) {
                allowedKnowledge.add(dependency.path("resourceId").asText());
            }
        }
        for (var reference : input.knowledgeReferences()) {
            if (!unique.add(reference.documentId() + ":" + reference.generation())) {
                throw ApiException.invalidField("knowledgeReferences", "同一资料版本不能重复选择。");
            }
            var citations = references.read(actor, reference.documentId(), reference.generation(), allowedKnowledge);
            chosen.addObject().put("documentId", reference.documentId()).put("generation", reference.generation());
            for (var source : citations) {
                if (knowledgeRemaining == 0) {
                    break;
                }
                String part = take(source.excerpt(), knowledgeRemaining);
                knowledgeRemaining -= part.codePointCount(0, part.length());
                var citation = new CitationView(source.chunkId(), source.documentId(), source.fileId(),
                    source.generation(), source.name(), source.page(), source.section(), part);
                content.append("\n\n知识资料：").append(source.name());
                if (source.page() != null) {
                    content.append("，第 ").append(source.page()).append(" 页");
                } else if (source.section() != null) {
                    content.append("，").append(source.section());
                }
                content.append('\n').append(part);
                blocks.add(new ContentBlock(UUID.randomUUID().toString(), "citation", null, blocks.size(), "1", "",
                    "completed", null, null, null,
                    json.convertValue(citation, new TypeReference<Map<String, Object>>() {
                    }), source.name(), null));
            }
        }
        fixed.put("sourceContext", content.toString());
        return List.copyOf(blocks);
    }

    public void bind(AuthContext actor, String message, List<String> ids) {
        attachments.bind(actor.enterpriseId(), message, ids, clock.instant());
        for (String id : ids) {
            files.retain(files.find(actor.enterpriseId(), id, true).orElseThrow(FileAccessService::unavailable),
                clock.instant());
        }
    }

    public void requireCurrent(AuthContext actor, RunRecord run) {
        for (var source : run.executionConfig().path("inputSourceFiles")) {
            var file = access.ready(actor, source.path("id").asText());
            if (!file.ownerUserId().equals(actor.userId()) || !file.purpose().equals("attachment") || !file.sha256()
                .equals(source.path("sha256").asText())) {
                throw FileAccessService.unavailable();
            }
        }
        for (var reference : run.executionConfig().path("inputKnowledgeReferences")) {
            references.read(actor, reference.path("documentId").asText(), reference.path("generation").asInt());
        }
    }

    private static String take(String text, int maximum) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(maximum, text.codePointCount(0, text.length()))));
    }

    private static ApiException notReady() {
        return new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY",
            "所选附件尚未完成检查和解析，请等待完成或移除后重试。");
    }
}
