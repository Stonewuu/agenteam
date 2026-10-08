package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * ExecutionEventMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ExecutionEventQueryRow {
    private String payloadJson;
    private String payloadHash;
    private String enterpriseId;
    private String conversationId;
}
