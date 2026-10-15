package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationArtifactService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 文件离开群策前重新核对原用户、精确成果与公开许可；不接受模型填写文件路径。 */
@Service
public class ChatFileSendService {
    private final CollaborationAccessService access;
    private final CollaborationArtifactService artifacts;
    private final FileAccessService files;
    private final FileContentStorage storage;
    private final ChatConversationService conversations;
    public ChatFileSendService(CollaborationAccessService access, CollaborationArtifactService artifacts, FileAccessService files,
            FileContentStorage storage, ChatConversationService conversations) {
        this.access = access;
        this.artifacts = artifacts;
        this.files = files;
        this.storage = storage;
        this.conversations = conversations;
    }

    public ChatPlatformAdapter.UploadFile file(ChatOutboundMessageRow row, JsonNode payload) {
        if (row.getTaskId() == null && row.getKind().equals("file_probe") && payload.path("syntheticFile").asBoolean()) {
            byte[] content = "name,attendance,salary\nexample,20,0\n".getBytes(StandardCharsets.UTF_8);
            return new ChatPlatformAdapter.UploadFile("群策能力验证.csv", "text/csv", content.length, () -> new ByteArrayInputStream(content));
        }
        if (row.getTaskId() == null || !row.getKind().equals("completed_file")) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_FILE_SEND_FORBIDDEN", "此发送记录没有可公开的成果文件。");
        }
        var task = access.require(row.getEnterpriseId(), row.getTaskId());
        if (!task.getOutputVisibility().equals("chat_full")
            || !ChatReplyPolicy.allowsFullResult(task, conversations.require(row.getEnterpriseId(), row.getConversationId()))) {
            throw new ApiException(HttpStatus.CONFLICT, row.getFirstSubmittedAt() == null ? "CHAT_FILE_SEND_FORBIDDEN" : "CHAT_SEND_UNKNOWN",
                "当前回复方式不允许发送成果附件。");
        }
        var artifact = artifacts.require(task, payload.path("artifactId").asText(), payload.path("artifactVersion").asInt());
        var file = files.ready(access.actor(task), artifact.getFileId());
        if (!file.id().equals(payload.path("fileId").asText()) || !file.sha256().equals(artifact.getSha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_CONTENT_CHANGED", "成果文件已变化，不能发送旧引用。");
        }
        return new ChatPlatformAdapter.UploadFile(file.originalName(), file.mediaType(), file.sizeBytes(), () -> storage.open(file));
    }
}
