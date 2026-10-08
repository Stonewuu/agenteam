package com.stonewu.agenteam.model.file.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 消息附件列表只读取公开文件信息。
 */
@Getter
@Setter
public class MessageAttachmentRowView {
    private String messageId;
    private String id;
    private String originalName;
    private String mediaType;
    private Long sizeBytes;
    private String status;
    private String errorCode;
    private Instant createdAt;
}
