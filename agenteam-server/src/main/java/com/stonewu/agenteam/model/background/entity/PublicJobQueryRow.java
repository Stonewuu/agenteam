package com.stonewu.agenteam.model.background.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * PublicJobMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class PublicJobQueryRow {
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private String kind;
    private String payloadJson;
    private String status;
    private String resultFileId;
    private String errorSummary;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
