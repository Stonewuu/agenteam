package com.stonewu.agenteam.model.memory.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * run_id 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MemorySourceQueryRow {
    private String conversationId;
    private String runId;
}
