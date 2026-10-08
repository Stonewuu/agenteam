package com.stonewu.agenteam.model.export.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 导出仅包含会话公开消息。
 */
@Getter
@Setter
public class ConversationExportRow {
    private String id;
    private Instant createdAt;
    private String role;
    private String attemptNo;
    private String status;
    private String content;
    private String blocksJson;
}
